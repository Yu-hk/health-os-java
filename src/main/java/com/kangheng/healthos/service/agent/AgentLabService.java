package com.kangheng.healthos.service.agent;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract.AgentScope;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract.AgentTool;
import com.kangheng.healthos.contract.agent.AgentLabContract;
import com.kangheng.healthos.mapper.agent.AgentMcpReceiptMapper;
import com.kangheng.healthos.mapper.patient.PatientMapper;
import com.kangheng.healthos.model.agent.AgentMcpReceipt;
import com.kangheng.healthos.model.patient.Patient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Agent Lab 的编排层：目录、协议三步校验、回执生命周期、指标。
 *
 * <p>对应原工程 {@code agent_lab_api.py}。原工程拉起 STDIO 子进程做 MCP 握手，
 * 这里换成**进程内**协议校验：三步 {@code initialize → tools/list → tools/call}
 * 都真实作用于进程内工具目录与业务数据，不启子进程。语义对齐，但可单测、不会因子进程
 * 挂掉而让整个目录接口不可用。
 *
 * <p><b>回执先落 running 再执行</b>：这是整块最要紧的一条。协议校验是"会留痕"的动作，
 * 若等跑完才一次性写入，进程崩在中途就什么都没留下。因此本类刻意<b>不加
 * {@code @Transactional}</b>——running 那行必须独立提交；执行完再单独 update。
 *
 * <p><b>失败也回回执，不抛异常</b>：越权工具、空输出、执行异常都收敛成
 * {@code status=failed + detail.error} 的回执（对齐原工程：调用方要能复盘"这次为什么没成"），
 * 只有请求本身的校验错误（serviceId 不符、患者越权、scopes 非法）才抛 HTTP 异常。
 */
@Service
public class AgentLabService {

    private static final Logger log = LoggerFactory.getLogger(AgentLabService.class);

    private final AgentMcpReceiptMapper receiptMapper;
    private final AgentLabToolService toolService;
    private final PatientMapper patientMapper;
    private final com.kangheng.healthos.spi.agent.McpServerBridge bridge;

    /** 进程内形态的便捷构造：单测与"没配 transport"的场景都走这里。 */
    public AgentLabService(AgentMcpReceiptMapper receiptMapper,
                           AgentLabToolService toolService,
                           PatientMapper patientMapper) {
        this(receiptMapper, toolService, patientMapper, new InProcessMcpBridge(toolService));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentLabService(AgentMcpReceiptMapper receiptMapper,
                           AgentLabToolService toolService,
                           PatientMapper patientMapper,
                           com.kangheng.healthos.spi.agent.McpServerBridge bridge) {
        this.receiptMapper = receiptMapper;
        this.toolService = toolService;
        this.patientMapper = patientMapper;
        this.bridge = bridge;
    }

    // ------------------------------------------------------------ 目录

    /**
     * 目录总视图。
     *
     * <p>当日次数与服务状态都按 {@code owner} 隔离：回执是患者域数据，
     * 跨账号聚合会泄露"别人今天探测过几次"这种活动信息，也违背全局的 owner 隔离约定。
     * 服务状态取本人最近一次 probe 回执派生，不混入他人观测。
     */
    public AgentLabContract.CatalogView catalog(String owner) {
        List<AgentMcpReceipt> today = receiptMapper.selectList(
                new LambdaQueryWrapper<AgentMcpReceipt>()
                        .eq(AgentMcpReceipt::getOwner, owner)
                        .ge(AgentMcpReceipt::getCreatedAt, AgentLabContract.startOfToday()));
        if (today == null) {
            today = List.of();
        }
        long probes = today.stream()
                .filter(r -> AgentLabContract.KIND_PROBE.equals(r.getKind())).count();
        long calls = today.stream()
                .filter(r -> AgentLabContract.KIND_CALL.equals(r.getKind())).count();
        double avgHandshake = today.stream()
                .filter(r -> AgentLabContract.KIND_PROBE.equals(r.getKind()))
                .mapToLong(AgentLabService::handshakeMsOf)
                .average().orElse(0.0);

        // 服务状态只看"最近一次 probe"，且不限定在当天——昨天探过今天没探，
        // 应显示为 stale 而不是 not_probed。
        AgentMcpReceipt latestProbe = receiptMapper.selectOne(
                new LambdaQueryWrapper<AgentMcpReceipt>()
                        .eq(AgentMcpReceipt::getOwner, owner)
                        .eq(AgentMcpReceipt::getKind, AgentLabContract.KIND_PROBE)
                        .orderByDesc(AgentMcpReceipt::getCreatedAt)
                        .last("limit 1"));

        AgentLabContract.Metrics metrics = new AgentLabContract.Metrics(
                AgentLabContract.agentDescriptors().size(),
                1,
                AgentLabContract.configuredTools().size(),
                calls,
                probes + calls,
                probes,
                round2(avgHandshake),
                AgentLabContract.TIMEZONE);
        AgentLabContract.ServiceDescriptor service = new AgentLabContract.ServiceDescriptor(
                AgentLabContract.SERVICE_ID, "患者业务只读服务", transportLabel(),
                serviceStatus(latestProbe), true, AgentLabContract.configuredTools().size());
        return new AgentLabContract.CatalogView(
                AgentLabContract.CATALOG_VERSION, AgentLabContract.SERVICE_ID,
                AgentLabContract.scopeWires(), AgentLabContract.agentDescriptors(),
                AgentLabContract.configuredTools(), metrics, List.of(service));
    }

    /**
     * 最近一次 probe 派生服务状态。
     *
     * <p>无记录 → not_probed；失败/仍在 running（崩溃留痕）→ unavailable；
     * 成功但超过 300 秒 → stale；否则 available。running 不当成 available 是刻意的：
     * 一条没跑完的握手不能对外宣称服务可用。
     */
    private static String serviceStatus(AgentMcpReceipt latestProbe) {
        if (latestProbe == null) {
            return "not_probed";
        }
        if (!AgentLabContract.STATUS_SUCCEEDED.equals(latestProbe.getStatus())) {
            return "unavailable";
        }
        OffsetDateTime at = latestProbe.getCompletedAt() != null
                ? latestProbe.getCompletedAt() : latestProbe.getCreatedAt();
        if (at == null || Duration.between(at, OffsetDateTime.now()).getSeconds()
                > AgentLabContract.STALE_SECONDS) {
            return "stale";
        }
        return "available";
    }

    /**
     * 目录里展示的传输层标签。
     *
     * <p>沿用原工程的大写下划线风格（{@code IN_PROCESS}），只是因为客户端按字面量显示，
     * 换个大小写会让"服务描述"看起来像换了个字段。{@code stdio} 形态显示为 {@code STDIO}。
     */
    private String transportLabel() {
        return bridge.transport().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static long handshakeMsOf(AgentMcpReceipt receipt) {
        Object value = Json.readMap(receipt.getDetail()).get("handshakeMs");
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // ------------------------------------------------------------ probe / call

    /** 探测：只跑 initialize → tools/list，不发 tools/call，output 恒为 null。 */
    public AgentLabContract.OperationView probe(String owner, String serviceId,
                                                AgentLabContract.ProbeRequest request) {
        Set<AgentScope> scopes = validate(serviceId, owner, request.patientId(), request.scopes());
        AgentMcpReceipt receipt = insertRunning(AgentLabContract.KIND_PROBE, owner,
                request.patientId(),
                buildRequest(AgentLabContract.KIND_PROBE, request.patientId(), scopes, null));
        return runProtocol(receipt, AgentLabContract.KIND_PROBE, owner, request.patientId(),
                scopes, null, Map.of());
    }

    /** 调用：三步之外再发 tools/call，真实执行一个只读工具。 */
    public AgentLabContract.OperationView call(String owner, String serviceId,
                                               AgentLabContract.CallRequest request) {
        Set<AgentScope> scopes = validate(serviceId, owner, request.patientId(), request.scopes());
        AgentMcpReceipt receipt = insertRunning(AgentLabContract.KIND_CALL, owner,
                request.patientId(),
                buildRequest(AgentLabContract.KIND_CALL, request.patientId(), scopes, request.tool()));
        return runProtocol(receipt, AgentLabContract.KIND_CALL, owner, request.patientId(),
                scopes, request.tool(), AgentLabToolService.sanitizeArguments(request.arguments()));
    }

    /**
     * 请求级校验：serviceId、scopes、患者归属。
     *
     * <p>顺序固定：serviceId 不符先报 {@code MCP_SERVICE_NOT_FOUND}；患者查不到一律
     * 返回 404 而不是 403——403 等于承认"这个 patientId 确实存在"，本身就是信息泄露。
     */
    private Set<AgentScope> validate(String serviceId, String owner, String patientId,
                                     List<String> rawScopes) {
        if (!AgentLabContract.SERVICE_ID.equals(serviceId)) {
            throw new BizException(AgentLabContract.MCP_SERVICE_NOT_FOUND,
                    "MCP 服务不存在", HttpStatus.NOT_FOUND);
        }
        Set<AgentScope> scopes = AgentLabContract.scopesOf(rawScopes);
        Patient patient = patientMapper.selectOne(new LambdaQueryWrapper<Patient>()
                .eq(Patient::getId, patientId)
                .eq(Patient::getCreatedBy, owner)
                .last("limit 1"));
        if (patient == null) {
            throw BizException.notFound("患者");
        }
        return scopes;
    }

    /**
     * 先提交一条 running 回执。
     *
     * <p>刻意不加事务：这一行必须独立落库。若把整个 probe/call 包进一个事务，
     * running 会等到方法返回才提交，进程在 tools/call 中途崩溃就一条痕迹都不剩。
     * 独立提交后，崩溃留下的 running 会在视图里显示为 interrupted。
     */
    private AgentMcpReceipt insertRunning(String kind, String owner, String patientId,
                                          Map<String, Object> request) {
        AgentMcpReceipt receipt = new AgentMcpReceipt();
        receipt.setId(Ids.newId("amcp"));
        receipt.setOwner(owner);
        receipt.setPatientId(patientId);
        receipt.setKind(kind);
        receipt.setStatus(AgentLabContract.STATUS_RUNNING);
        receipt.setRequest(Json.write(request));
        receipt.setCreatedAt(OffsetDateTime.now());
        receiptMapper.insert(receipt);
        return receipt;
    }

    /** 回执只记录协议层元信息；原始 arguments 可能夹带 PII，一律不落库。 */
    private Map<String, Object> buildRequest(String kind, String patientId,
                                             Set<AgentScope> scopes, String tool) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("serviceId", AgentLabContract.SERVICE_ID);
        request.put("kind", kind);
        request.put("patientId", patientId);
        request.put("scopes", scopes.stream().map(AgentScope::wire).toList());
        if (tool != null) {
            request.put("tool", tool);
        }
        return request;
    }

    /**
     * 工具参数白名单集中在 {@link AgentLabToolService#sanitizeArguments}：
     * 进程内与子进程回调两条链路必须过同一份过滤，不能各写一套。
     */

    // ------------------------------------------------------------ 协议三步

    /**
     * 协议三步校验：initialize → tools/list →（call 才有 tools/call）。
     *
     * <p>三步都通过 {@link com.kangheng.healthos.spi.agent.McpServerBridge} 走，
     * 所以"进程内校验"和"真子进程校验"用的是同一段编排代码：
     * <ul>
     *   <li>{@code in-process}：{@code discover()} 枚举本进程目录；</li>
     *   <li>{@code stdio}：子进程自己在 {@code tools/list} 里声明清单，数据仍回调本进程读。</li>
     * </ul>
     *
     * <p><b>发现集与授权集的比对</b>：{@code tools/list} 的结果来自服务端自己的声明，
     * 授权集来自 {@code AgentLabContract.authorizedToolWires} 那张独立白名单。
     * 两边刻意不是一份代码，漂移才会被这条规则抓住。
     *
     * <p>失败阶段的归属靠 {@code McpBridgeException.stage()}：子进程起不来时阶段未知，
     * 归到发起它的那一步；{@code tools/list} 失败时 {@code initialize} 已经成功，
     * 但它的精确耗时拿不到，记 0——宁可少一个精确数字，也不编一个。
     */
    private AgentLabContract.OperationView runProtocol(AgentMcpReceipt receipt, String kind,
                                                       String owner, String patientId,
                                                       Set<AgentScope> scopes, String toolWire,
                                                       Map<String, Object> args) {
        List<Map<String, Object>> events = new ArrayList<>();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("transport", bridge.transport());
        Set<String> scopeWires = scopeWires(scopes);
        Set<String> authorized = AgentLabContract.authorizedToolWires(scopes);
        long started = System.nanoTime();

        // ---- 第 1、2 步：握手 + 发现
        com.kangheng.healthos.spi.agent.McpServerBridge.Handshake handshake;
        try {
            handshake = bridge.handshake(scopeWires, owner, patientId);
        } catch (com.kangheng.healthos.spi.agent.McpBridgeException ex) {
            log.warn("Agent Lab 握手失败 receiptId={} stage={} code={}",
                    receipt.getId(), ex.stage(), ex.code());
            String stage = ex.stage();
            if (!"initialize".equals(stage) && stage != null) {
                // 走到 tools/list 才挂，说明 initialize 已经成功
                events.add(event("initialize", "ok", 0L));
                events.add(event("tools/list", "error", elapsedMs(started)));
            } else {
                events.add(event("initialize", "error", elapsedMs(started)));
            }
            detail.put("handshakeMs", elapsedMs(started));
            detail.put("failedAt", stage == null ? "initialize" : stage);
            return finish(receipt, AgentLabContract.STATUS_FAILED,
                    mapBridgeError(ex.code()), events, detail, null);
        } catch (RuntimeException ex) {
            log.warn("Agent Lab 握手异常 receiptId={}", receipt.getId());
            events.add(event("initialize", "error", elapsedMs(started)));
            detail.put("handshakeMs", elapsedMs(started));
            return finish(receipt, AgentLabContract.STATUS_FAILED,
                    AgentLabContract.MCP_UNAVAILABLE, events, detail, null);
        }

        detail.put("protocolVersion", handshake.protocolVersion());
        detail.put("serverInfo", Map.of("name", handshake.serverName(),
                "version", handshake.serverVersion()));
        detail.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        detail.put("handshakeMs", handshake.handshakeMs());
        events.add(event("initialize", "ok", handshake.initializeMs()));

        Set<String> discovered = new LinkedHashSet<>(handshake.tools());
        boolean discoveryOk = discovered.equals(authorized);
        events.add(event("tools/list", discoveryOk ? "ok" : "failed",
                Math.max(0L, handshake.handshakeMs() - handshake.initializeMs())));
        // 只记工具名与差异，不记任何调用参数：工具名是固定枚举，不含患者数据。
        detail.put("declaredTools", List.copyOf(discovered));
        if (!discoveryOk) {
            List<String> missing = new ArrayList<>(authorized);
            missing.removeAll(discovered);
            List<String> extra = new ArrayList<>(discovered);
            extra.removeAll(authorized);
            detail.put("missingTools", List.copyOf(missing));
            detail.put("extraTools", List.copyOf(extra));
            return finish(receipt, AgentLabContract.STATUS_FAILED,
                    AgentLabContract.AGENT_DISCOVERY_MISMATCH, events, detail, null);
        }
        if (AgentLabContract.KIND_PROBE.equals(kind)) {
            // probe 固定不发 tools/call，output 为 null。
            return finish(receipt, AgentLabContract.STATUS_SUCCEEDED, null, events, detail, null);
        }

        // ---- 第 3 步：tools/call
        long s3 = System.nanoTime();
        if (toolWire == null || !authorized.contains(toolWire)) {
            // 对齐原工程语义：越权工具不抛异常，仍返回一条 failed 回执。
            events.add(event("tools/call", "rejected", elapsedMs(s3)));
            return finish(receipt, AgentLabContract.STATUS_FAILED,
                    AgentLabContract.MCP_TOOL_REJECTED, events, detail, null);
        }
        Map<String, Object> structured;
        try {
            structured = bridge.callTool(scopeWires, toolWire, owner, patientId, args);
        } catch (com.kangheng.healthos.spi.agent.McpBridgeException ex) {
            // 桥接层的错误码不外泄：对外仍只讲契约里那几个（避免"同一件事两个名字"）。
            log.warn("Agent Lab 工具调用失败 receiptId={} stage={} code={}",
                    receipt.getId(), ex.stage(), ex.code());
            events.add(event("tools/call", "error", elapsedMs(s3)));
            detail.put("failedAt", "tools/call");
            return finish(receipt, AgentLabContract.STATUS_FAILED,
                    mapBridgeError(ex.code()), events, detail, null);
        } catch (RuntimeException ex) {
            // 执行异常统一收敛为 MCP_UNAVAILABLE，绝不把堆栈/SQL/连接串写进 detail。
            log.warn("Agent Lab 工具执行异常 receiptId={}", receipt.getId());
            events.add(event("tools/call", "error", elapsedMs(s3)));
            return finish(receipt, AgentLabContract.STATUS_FAILED,
                    AgentLabContract.MCP_UNAVAILABLE, events, detail, null);
        }
        if (structured == null || structured.isEmpty()) {
            events.add(event("tools/call", "invalid", elapsedMs(s3)));
            return finish(receipt, AgentLabContract.STATUS_FAILED,
                    AgentLabContract.MCP_OUTPUT_INVALID, events, detail, null);
        }
        events.add(event("tools/call", "ok", elapsedMs(s3)));
        String json = Json.write(structured);
        detail.put("outputHash", Ids.sha256Hex(json));
        detail.put("outputBytes", json.getBytes(StandardCharsets.UTF_8).length);
        return finish(receipt, AgentLabContract.STATUS_SUCCEEDED, null, events, detail,
                new AgentLabContract.ToolCallOutput(structured));
    }

    /** 范围枚举 → wire 名集合（子进程只认字符串，接口层也不该依赖契约枚举）。 */
    private static Set<String> scopeWires(Set<AgentScope> scopes) {
        Set<String> wires = new LinkedHashSet<>();
        for (AgentScope scope : scopes) {
            wires.add(scope.wire());
        }
        return wires;
    }

    /**
     * 桥接错误码 → 契约错误码。
     *
     * <p>对外只讲 {@code AgentLabContract} 里冻结的那几个码：客户端不该因为
     * "服务端换了传输层"而收到新错误名。{@code MCP_OUTPUT_INVALID} 原样透传，
     * 其余（超时/进程退出/协议错误/工具执行失败）一律归成 {@code MCP_UNAVAILABLE}——
     * 对调用方来说，它们都是"这次没拿到结果，可以重试或换形态"。
     */
    private static String mapBridgeError(String bridgeCode) {
        return AgentLabContract.MCP_OUTPUT_INVALID.equals(bridgeCode)
                ? AgentLabContract.MCP_OUTPUT_INVALID
                : AgentLabContract.MCP_UNAVAILABLE;
    }

    /** 执行完统一改状态、写 detail 与 completedAt，再返回 {receipt, output}。 */
    private AgentLabContract.OperationView finish(AgentMcpReceipt receipt, String status,
                                                  String error, List<Map<String, Object>> events,
                                                  Map<String, Object> detail, Object output) {
        detail.put("events", events);
        if (error != null) {
            detail.put("error", error);
        }
        // 异常若发生在握手完成前，补 0 保证 detail.handshakeMs 恒在、当日均值可算。
        detail.putIfAbsent("handshakeMs", 0L);
        receipt.setStatus(status);
        receipt.setDetail(Json.write(detail));
        receipt.setCompletedAt(OffsetDateTime.now());
        receiptMapper.updateById(receipt);
        return new AgentLabContract.OperationView(receiptView(receipt), output);
    }

    private static Map<String, Object> event(String method, String status, long durationMs) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("method", method);
        item.put("status", status);
        item.put("durationMs", durationMs);
        return item;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    // ------------------------------------------------------------ 回执视图

    /**
     * 回执视图。
     *
     * <p>running 且创建超过 35 秒 → 视图显示 interrupted，但<b>库里仍是 running</b>：
     * 超时只是"这次请求大概没回来"的观测结论，不能擅自把一条可能还在途的操作写成终态。
     */
    public AgentLabContract.ReceiptView receiptView(AgentMcpReceipt receipt) {
        String status = receipt.getStatus();
        if (AgentLabContract.STATUS_RUNNING.equals(status) && receipt.getCreatedAt() != null
                && Duration.between(receipt.getCreatedAt(), OffsetDateTime.now()).getSeconds()
                        > AgentLabContract.INTERRUPTED_SECONDS) {
            status = "interrupted";
        }
        return new AgentLabContract.ReceiptView(
                receipt.getId(), receipt.getPatientId(), receipt.getKind(), status,
                Json.readMap(receipt.getRequest()), Json.readMap(receipt.getDetail()),
                receipt.getCreatedAt() == null ? null : String.valueOf(receipt.getCreatedAt()),
                receipt.getCompletedAt() == null ? null : String.valueOf(receipt.getCompletedAt()));
    }
}
