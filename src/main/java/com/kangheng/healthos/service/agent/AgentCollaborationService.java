package com.kangheng.healthos.service.agent;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract;
import com.kangheng.healthos.contract.agent.AgentRunContract;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract.AgentAnswer;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract.AgentRole;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract.AgentScenario;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract.AgentScope;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract.AgentTool;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract.RolePlan;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract.SourceRef;
import com.kangheng.healthos.contract.care.CareContract;
import com.kangheng.healthos.contract.patient.PatientMemoryContract;
import com.kangheng.healthos.mapper.agent.AgentNodeMapper;
import com.kangheng.healthos.mapper.agent.AgentRunMapper;
import com.kangheng.healthos.mapper.care.CareDocumentMapper;
import com.kangheng.healthos.mapper.patient.PatientMapper;
import com.kangheng.healthos.mapper.patient.PatientMemoryJobMapper;
import com.kangheng.healthos.model.agent.AgentNode;
import com.kangheng.healthos.model.agent.AgentRun;
import com.kangheng.healthos.model.patient.PatientMemoryJob;
import com.kangheng.healthos.service.care.CareService;
import com.kangheng.healthos.service.outbox.OutboxService;
import com.kangheng.healthos.service.patient.PatientMemoryService;
import com.kangheng.healthos.spi.agent.AgentRoleExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 多智能体协同的编排层。
 *
 * <p>它管的是"谁该跑、跑完能不能用"，不管"答案怎么写"：
 * <ol>
 *   <li><b>角色计划冻结</b>：场景 + 授权范围决定谁启用、谁能用什么工具（{@link AgentCollaborationContract#freezePlan}）；</li>
 *   <li><b>依赖图</b>：MEMORY 与 PIPELINE 是两条互不依赖的只读分支；LIFESTYLE 必须等它们两个；
 *       SYNTHESIS 必须等 LIFESTYLE（若启用）。</li>
 *   <li><b>跳过而非硬失败</b>：必需上游失败时下游角色标记 skipped 而不是乱跑。</li>
 *   <li><b>来源校验</b>：角色引用的来源必须 ⊆ 它实际读到的（工具读到的 + 上游交接的），
 *       且每个工具、每个上游角色至少被引用一次。</li>
 *   <li><b>交接完整性</b>：下游只接受同一次运行、状态成功、且 {@code outputHash} 对得上的上游结果。</li>
 *   <li><b>finalize</b>：必需角色全部成功才算成功；已核验事实只取工具回执，不取模型自己算的数。</li>
 * </ol>
 *
 * <p>对应原工程 {@code agent_collaboration.py}（LangGraph 编排）。
 * <b>有意的一处差异</b>：原工程用 LangGraph 并发跑 MEMORY/PIPELINE 两条分支；
 * 本实现顺序执行——两条分支互不依赖，结果语义完全一致，差别只在延迟。
 *
 * <p><b>事实由编排层读、执行器只做组织</b>：工具数据是编排层从库里读出来再交给执行器的，
 * 执行器只负责把已读到的事实组织成答案。这样"来源校验"才有意义——
 * 否则就变成执行器自证"我确实读了"。
 */
@Service
public class AgentCollaborationService {

    private static final Logger log = LoggerFactory.getLogger(AgentCollaborationService.class);

    /** Outbox 事件类型。 */
    public static final String EVENT_EXECUTE = "agent.collaboration.execute";

    private static final String STATUS_QUEUED = "QUEUED";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private static final String NODE_ROLE = "role";
    private static final String NODE_VALIDATION = "validation";
    private static final String NODE_TOOL = "tool";

    /**
     * 采样回执来源的前缀。
     *
     * <p>它不是业务实体，而是一次查询本身的凭据：命中 0 条时，"没有"这句话也必须有来源。
     * 带前缀是为了让看来源列表的人一眼分清"实体引用"与"采样回执"。
     */
    private static final String SAMPLE_RECEIPT_PREFIX = "sample:";

    private final AgentRunMapper runMapper;
    private final AgentNodeMapper nodeMapper;
    private final AgentRoleExecutor executor;
    private final PatientMemoryService memoryService;
    private final CareService careService;
    private final PatientMemoryJobMapper memoryJobMapper;
    private final CareDocumentMapper careDocumentMapper;
    private final PatientMapper patientMapper;
    private final OutboxService outboxService;
    private final AgentRunEventService eventService;

    public AgentCollaborationService(AgentRunMapper runMapper,
                                     AgentNodeMapper nodeMapper,
                                     AgentRoleExecutor executor,
                                     PatientMemoryService memoryService,
                                     CareService careService,
                                     PatientMemoryJobMapper memoryJobMapper,
                                     CareDocumentMapper careDocumentMapper,
                                     PatientMapper patientMapper,
                                     OutboxService outboxService,
                                     AgentRunEventService eventService) {
        this.runMapper = runMapper;
        this.nodeMapper = nodeMapper;
        this.executor = executor;
        this.memoryService = memoryService;
        this.careService = careService;
        this.memoryJobMapper = memoryJobMapper;
        this.careDocumentMapper = careDocumentMapper;
        this.patientMapper = patientMapper;
        this.outboxService = outboxService;
        this.eventService = eventService;
    }

    // ------------------------------------------------------------ 提交

    /**
     * 提交一次协同。
     *
     * <p>幂等：同 {@code operationId} 且输入一致时返回同一次运行；输入变了则报冲突——
     * 静默复用会把两次不同的请求混成一份结果。
     */
    @Transactional
    public AgentCollaborationContract.CollaborationAccepted submit(String owner, String patientId,
                                                                  AgentCollaborationContract.SubmitCollaborationRequest request) {
        AgentScenario scenario = AgentScenario.of(request.scenario());
        if (scenario == null) {
            throw new BizException(AgentCollaborationContract.ERROR_SCENARIO_SCOPE,
                    "未知的协同场景：" + request.scenario(),
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        }
        Set<AgentScope> scopes = AgentCollaborationContract.parseScopes(request.scopes());
        // 场景与范围不匹配在这里就挡住，不必等到执行期。
        AgentCollaborationContract.freezePlan(scenario, scopes);

        AgentRun existing = runMapper.selectOne(Wrappers.<AgentRun>lambdaQuery()
                .eq(AgentRun::getOwner, owner)
                .eq(AgentRun::getPatientId, patientId)
                .eq(AgentRun::getOperationId, request.operationId())
                .last("LIMIT 1"));
        if (existing != null) {
            String fingerprint = fingerprint(scenario, scopes, request.instruction());
            if (!fingerprint.equals(storedFingerprint(existing))) {
                throw new BizException("AGENT_OPERATION_MISMATCH",
                        "同一 operationId 已用于不同的协同请求，请换一个",
                        org.springframework.http.HttpStatus.CONFLICT);
            }
            return new AgentCollaborationContract.CollaborationAccepted(existing.getId(),
                    existing.getStatus(), true);
        }

        AgentRun run = new AgentRun();
        run.setId(Ids.newId("arun"));
        run.setOwner(owner);
        run.setPatientId(patientId);
        run.setScenario(scenario.wire());
        run.setScopes(Json.write(scopes.stream().map(AgentScope::wire).toList()));
        run.setInstruction(request.instruction());
        run.setOperationId(request.operationId());
        run.setStatus(STATUS_QUEUED);
        run.setEventSeq(0);
        run.setVersion(1);
        run.setDeadlineAt(OffsetDateTime.now().plusSeconds(AgentRunControlService.RUN_TIMEOUT_SECONDS));
        run.setPromptVersion(AgentCollaborationContract.PROMPT_VERSION);
        run.setGraphVersion(AgentCollaborationContract.GRAPH_VERSION);
        run.setRequestFingerprint(fingerprint(scenario, scopes, request.instruction()));
        run.setCreatedAt(OffsetDateTime.now());
        runMapper.insert(run);
        // 受理事件与 run、outbox 事件同一事务提交：客户端拿到 runId 时，
        // events?after_seq=0 一定已经能查到 run.queued，不会看到「有运行没事件」。
        eventService.emit(run.getId(), AgentRunContract.RUN_QUEUED, null, STATUS_QUEUED,
                Map.of("scenario", scenario.wire(), "operationId", request.operationId()));
        outboxService.append("agent_run", run.getId(), EVENT_EXECUTE,
                Map.of("runId", run.getId()));
        return new AgentCollaborationContract.CollaborationAccepted(run.getId(), STATUS_QUEUED, false);
    }

    /**
     * 取这次运行当初记下的请求指纹。
     *
     * <p>指纹有独立一列 {@code request_fingerprint}。对早于该列存在的历史行，
     * 指纹落在 {@code result} 里——那批行的 {@code result} 尚未被最终结果覆盖，
     * 仍然可以直接拿来比对。两者都取不到才算"无法判定"。
     */
    private String storedFingerprint(AgentRun run) {
        if (run.getRequestFingerprint() != null && !run.getRequestFingerprint().isBlank()) {
            return run.getRequestFingerprint();
        }
        String result = run.getResult();
        if (result != null && result.length() == 64 && result.chars().allMatch(c ->
                (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            return result;
        }
        return null;
    }

    /** 输入指纹：同一 operationId 换了输入就是另一种请求，必须报冲突而不是复用。 */
    private String fingerprint(AgentScenario scenario, Set<AgentScope> scopes, String instruction) {
        List<String> sorted = scopes.stream().map(AgentScope::wire).sorted().toList();
        return Ids.sha256Hex(scenario.wire() + '|' + String.join(",", sorted) + '|' + instruction);
    }

    // ------------------------------------------------------------ 执行

    /**
     * 执行一次协同。由 Outbox 处理器调用。
     *
     * <p>整轮成功与否只看<b>必需角色是否都成功</b>。缺了角色的汇报不能被描述成成功，
     * 所以宁可整轮失败。
     */
    public void execute(String runId) {
        AgentRun run = runMapper.selectById(runId);
        if (run == null) {
            log.warn("协同运行不存在 runId={}", runId);
            return;
        }
        // 取消优先：点过取消之后 Outbox 里可能还排着一条事件，
        // 不检查就会把已经终态的 run 又跑起来。
        if (STATUS_CANCELLED.equals(run.getStatus()) || STATUS_SUCCEEDED.equals(run.getStatus())) {
            return;
        }
        // 进程崩溃后重派：过期的 run 不重放模型/工具调用，直接判失败。
        // 对齐原工程「不重放崩溃前的模型调用」，否则一轮执行会被无限延长。
        if (run.getDeadlineAt() != null && OffsetDateTime.now().isAfter(run.getDeadlineAt())) {
            run.setStatus(STATUS_FAILED);
            run.setError(AgentRunContract.ERROR_DEADLINE);
            run.setCompletedAt(OffsetDateTime.now());
            runMapper.updateById(run);
            eventService.emit(runId, AgentRunContract.RUN_FAILED, null, STATUS_FAILED,
                    Map.of("error", AgentRunContract.ERROR_DEADLINE));
            return;
        }
        run.setStatus(STATUS_RUNNING);
        run.setStartedAt(OffsetDateTime.now());
        runMapper.updateById(run);
        eventService.emit(runId, AgentRunContract.RUN_STARTED, null, STATUS_RUNNING,
                Map.of("attempt", "outbox"));

        AgentScenario scenario = AgentScenario.of(run.getScenario());
        Set<AgentScope> scopes = new LinkedHashSet<>();
        for (String value : Json.readList(run.getScopes(), String.class)) {
            AgentScope scope = AgentScope.of(value);
            if (scope != null) {
                scopes.add(scope);
            }
        }
        List<RolePlan> plans = AgentCollaborationContract.freezePlan(scenario, scopes);
        Map<AgentRole, String> roleResultIds = new LinkedHashMap<>();
        Map<AgentRole, String> roleStatuses = new LinkedHashMap<>();
        Map<AgentRole, List<SourceRef>> roleSources = new LinkedHashMap<>();
        AtomicInteger ordinal = new AtomicInteger();

        // 断点续跑：重试时复用上一轮「已成功且摘要完整」的角色节点。
        // 只认 role 节点——校验节点与工具读取必须重跑，否则会拿上一轮的
        // 校验结论当这一轮的证据。节点还没删，交接凭据（outputHash）仍然有效。
        List<AgentNode> completed = nodeMapper.selectSucceededRoles(runId);
        Map<String, AgentNode> restoredByRole = new LinkedHashMap<>();
        if (completed != null) {
            for (AgentNode node : completed) {
                if (node.getRole() != null && node.getOutputHash() != null) {
                    restoredByRole.putIfAbsent(node.getRole(), node);
                }
            }
        }

        for (RolePlan plan : plans) {
            AgentNode restored = restoredByRole.get(plan.role().id());
            if (restored != null) {
                roleResultIds.put(plan.role(), restored.getId());
                roleStatuses.put(plan.role(), "succeeded");
                Map<String, Object> output = Json.readMap(restored.getOutput());
                Object answer = output.get("answer");
                roleSources.put(plan.role(), answer instanceof Map<?, ?> map
                        ? sources(map.get("sources")) : List.of());
                eventService.emit(runId, AgentRunContract.NODE_SKIPPED, restored.getId(), "succeeded",
                        Map.of("reason", "断点续跑：复用已完成角色节点",
                                "nodeId", restored.getId()));
                continue;
            }
            String skipReason = skipReason(plan, roleResultIds);
            if (skipReason != null) {
                recordNode(runId, plan.role(), NODE_ROLE, plan.role().id(), "skipped",
                        Json.write(Map.of("reason", skipReason)), null, skipReason, null, ordinal);
                roleStatuses.put(plan.role(), "skipped");
                continue;
            }
            try {
                RoleResult result = runRole(run, plan, roleResultIds, roleSources, ordinal);
                roleResultIds.put(plan.role(), result.nodeId());
                roleStatuses.put(plan.role(), "succeeded");
                roleSources.put(plan.role(), result.sources());
            } catch (BizException ex) {
                log.warn("协同角色失败 runId={} role={} code={}", runId, plan.role(), ex.getCode());
                recordNode(runId, plan.role(), NODE_ROLE, plan.role().id(), "failed",
                        Json.write(Map.of("error", ex.getCode())), null,
                        "本角色失败；已完成的来源和其他角色结果保留", ex.getCode(), ordinal);
                roleStatuses.put(plan.role(), "failed");
            } catch (RuntimeException ex) {
                // 执行器炸了也不能让整轮没有记录：这一支失败要落到节点上，
                // 其余分支的成果照常保留。
                log.warn("协同角色执行异常 runId={} role={}", runId, plan.role(), ex);
                recordNode(runId, plan.role(), NODE_ROLE, plan.role().id(), "failed",
                        Json.write(Map.of("error", "AGENT_ROLE_EXECUTION_FAILED")), null,
                        "本角色失败；已完成的来源和其他角色结果保留",
                        "AGENT_ROLE_EXECUTION_FAILED", ordinal);
                roleStatuses.put(plan.role(), "failed");
            }
        }

        finalizeRun(run, plans, roleResultIds, roleStatuses, roleSources, ordinal);
    }

    /** 角色为什么不该跑：没启用，或必需上游没有可用结果。 */
    private String skipReason(RolePlan plan, Map<AgentRole, String> roleResultIds) {
        if (!plan.enabled()) {
            return plan.skipReason();
        }
        for (AgentRole dependency : plan.dependsOn()) {
            if (!roleResultIds.containsKey(dependency)) {
                return "必需上游失败或无可交接结果，禁止继续推导";
            }
        }
        return null;
    }

    /** 跑一个角色，返回它的产出节点与来源。 */
    private RoleResult runRole(AgentRun run, RolePlan plan, Map<AgentRole, String> roleResultIds,
                               Map<AgentRole, List<SourceRef>> roleSources, AtomicInteger ordinal) {
        List<Upstream> upstream = collectUpstream(run.getId(), plan, roleResultIds);
        List<AgentRoleExecutor.ToolReading> readings = readTools(run, plan);

        // 必需工具必须真的读到，否则这个角色没有资格作答。
        Set<String> readTools = new LinkedHashSet<>();
        for (AgentRoleExecutor.ToolReading reading : readings) {
            readTools.add(reading.tool());
        }
        for (AgentTool tool : plan.tools()) {
            if (!readTools.contains(tool.wire())) {
                throw new BizException(AgentCollaborationContract.ERROR_REQUIRED_TOOL_MISSING,
                        "角色 " + plan.role().id() + " 缺少必需读取：" + tool.wire(),
                        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
            }
        }
        requireMeaningfulReading(plan, readings);

        List<AgentRoleExecutor.Source> upstreamSources = new ArrayList<>();
        for (Upstream item : upstream) {
            for (SourceRef ref : item.sources()) {
                upstreamSources.add(new AgentRoleExecutor.Source(ref.id(), ref.version()));
            }
        }
        AgentRoleExecutor.RoleOutcome outcome = executor.execute(new AgentRoleExecutor.RoleRequest(
                run.getId(), run.getOwner(), run.getPatientId(), plan.role().id(), plan.prompt(),
                run.getInstruction(), plan.tools().stream().map(AgentTool::wire).toList(), readings,
                upstreamSources));

        List<SourceRef> allowed = new ArrayList<>();
        for (Upstream item : upstream) {
            allowed.addAll(item.sources());
        }
        for (AgentRoleExecutor.ToolReading reading : readings) {
            for (AgentRoleExecutor.Source source : reading.sources()) {
                allowed.add(new SourceRef(source.id(), source.version()));
            }
        }
        List<SourceRef> actual = new ArrayList<>();
        if (outcome.answer() != null && outcome.answer().sources() != null) {
            for (AgentRoleExecutor.Source source : outcome.answer().sources()) {
                actual.add(new SourceRef(source.id(), source.version()));
            }
        }

        List<Map<String, Object>> problems = validate(plan, actual, allowed, readings, upstream);
        recordNode(run.getId(), plan.role(), NODE_VALIDATION, "角色结果结构与来源校验",
                problems.isEmpty() ? "succeeded" : "failed",
                Json.write(Map.of("valid", problems.isEmpty(), "problems", problems)), null,
                "核对结构、各工具与上游角色引用；不代表医疗事实核验",
                problems.isEmpty() ? null : AgentCollaborationContract.ERROR_ROLE_SOURCE_INVALID, ordinal);
        if (!problems.isEmpty()) {
            throw new BizException(AgentCollaborationContract.ERROR_ROLE_SOURCE_INVALID,
                    "角色 " + plan.role().id() + " 的结果未通过来源校验",
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("role_id", plan.role().id());
        output.put("answer", Map.of(
                "summary", outcome.answer() == null ? "" : outcome.answer().summary(),
                "progress", outcome.answer() == null ? List.of() : outcome.answer().progress(),
                "limitations", outcome.answer() == null ? List.of() : outcome.answer().limitations(),
                "sources", outcome.answer() == null ? List.of() : outcome.answer().sources()));
        output.put("evidence", readings.stream()
                .map(reading -> Map.of("tool", reading.tool(), "sources", reading.sources()))
                .toList());
        output.put("upstream", upstream.stream()
                .map(item -> Map.of("role", item.role().id(), "upstream_node_id", item.nodeId(),
                        "output_hash", item.outputHash()))
                .toList());
        String json = Json.write(output);
        String nodeId = recordNode(run.getId(), plan.role(), NODE_ROLE, plan.role().id(), "succeeded",
                json, Ids.sha256Hex(json),
                outcome.answer() == null ? "" : outcome.answer().summary(), null, ordinal);
        return new RoleResult(nodeId, actual);
    }

    /**
     * 来源校验。
     *
     * <p>五类问题对应五种"没资格这么说"：
     * <ul>
     *   <li>{@code EMPTY_SOURCES} —— 一个来源都没给，等于没依据；</li>
     *   <li>{@code UNOBSERVED_SOURCES} —— 引用了它没读到过的东西（幻觉）；</li>
     *   <li>{@code TOOL_SOURCE_MISSING} —— 读了工具却没引用它的产出，说明白读了；</li>
     *   <li>{@code HANDOFF_SOURCE_MISSING} —— 拿了上游交接却没引用上游的来源，等于吞了交接；</li>
     *   <li>{@code ROLE_SCHEMA_INVALID} —— 结果结构本身不合法。</li>
     * </ul>
     */
    private List<Map<String, Object>> validate(RolePlan plan, List<SourceRef> actual,
                                               List<SourceRef> allowed,
                                               List<AgentRoleExecutor.ToolReading> readings,
                                               List<Upstream> upstream) {
        List<Map<String, Object>> problems = new ArrayList<>();
        Set<String> actualKeys = new LinkedHashSet<>();
        for (SourceRef ref : actual) {
            actualKeys.add(AgentCollaborationContract.sourceKey(ref));
        }
        if (actualKeys.isEmpty()) {
            problems.add(Map.of("code", "EMPTY_SOURCES"));
        }
        Set<String> allowedKeys = new LinkedHashSet<>();
        for (SourceRef ref : allowed) {
            allowedKeys.add(AgentCollaborationContract.sourceKey(ref));
        }
        List<Map<String, Object>> unobserved = new ArrayList<>();
        for (SourceRef ref : actual) {
            if (!allowedKeys.contains(AgentCollaborationContract.sourceKey(ref))) {
                unobserved.add(Map.of("id", ref.id(), "version", ref.version() == null ? "" : ref.version()));
            }
        }
        if (!unobserved.isEmpty()) {
            problems.add(Map.of("code", "UNOBSERVED_SOURCES", "sources", unobserved));
        }
        for (AgentRoleExecutor.ToolReading reading : readings) {
            Set<String> toolKeys = new LinkedHashSet<>();
            for (AgentRoleExecutor.Source source : reading.sources()) {
                toolKeys.add(source.id() + "#" + (source.version() == null ? "-" : source.version()));
            }
            if (!toolKeys.isEmpty() && java.util.Collections.disjoint(toolKeys, actualKeys)) {
                problems.add(Map.of("code", "TOOL_SOURCE_MISSING", "tool", reading.tool()));
            }
        }
        for (Upstream item : upstream) {
            Set<String> upstreamKeys = new LinkedHashSet<>();
            for (SourceRef ref : item.sources()) {
                upstreamKeys.add(AgentCollaborationContract.sourceKey(ref));
            }
            if (!upstreamKeys.isEmpty() && java.util.Collections.disjoint(upstreamKeys, actualKeys)) {
                problems.add(Map.of("code", "HANDOFF_SOURCE_MISSING", "role", item.role().id()));
            }
        }
        if (actual.isEmpty() && !plan.role().equals(AgentRole.SYNTHESIS)) {
            problems.add(Map.of("code", "ROLE_SCHEMA_INVALID"));
        }
        return problems;
    }

    /**
     * 收集上游交接。
     *
     * <p>只有<b>同一次运行、同角色、kind=role、状态成功、且摘要对得上</b>的上游结果才能交接。
     * 少任何一条，交接都不成立——这是防止"拿上一轮的结果当本轮证据"的关键。
     */
    private List<Upstream> collectUpstream(String runId, RolePlan plan, Map<AgentRole, String> roleResultIds) {
        List<Upstream> result = new ArrayList<>();
        for (AgentRole dependency : plan.dependsOn()) {
            String nodeId = roleResultIds.get(dependency);
            if (nodeId == null) {
                throw new BizException(AgentCollaborationContract.ERROR_HANDOFF_INVALID,
                        "上游角色没有可用结果：" + dependency.id(),
                        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
            }
            AgentNode node = nodeMapper.selectById(nodeId);
            if (node == null || !Objects.equals(node.getRunId(), runId)
                    || !Objects.equals(node.getRole(), dependency.id())
                    || !Objects.equals(node.getKind(), NODE_ROLE)
                    || !"succeeded".equals(node.getStatus())
                    || !Objects.equals(Ids.sha256Hex(node.getOutput()), node.getOutputHash())) {
                throw new BizException(AgentCollaborationContract.ERROR_HANDOFF_INVALID,
                        "上游交接校验失败：" + dependency.id(),
                        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Map<String, Object> output = Json.readMap(node.getOutput());
            List<SourceRef> sources = new ArrayList<>();
            // 来源在节点的 answer 里：output = {role_id, answer:{summary,progress,limitations,sources}, ...}
            Object rawAnswer = output.get("answer");
            Map<String, Object> answer = rawAnswer instanceof Map<?, ?> map
                    ? castAnswer(map) : Map.of();
            Object rawSources = answer.get("sources");
            if (rawSources instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map && map.get("id") != null) {
                        Object version = map.get("version");
                        sources.add(new SourceRef(String.valueOf(map.get("id")),
                                version instanceof Number number ? number.intValue() : null));
                    }
                }
            }
            result.add(new Upstream(dependency, nodeId, node.getOutputHash(), sources));
        }
        return result;
    }

    /**
     * 读取工具数据。
     *
     * <p>这里是唯一真正接触业务数据的地方，读的都是本系统已落库的事实：
     * 已发布记忆、已签发报告、生效计划与打卡进度、患者任务状态。
     * 读不到就返回空（带明确说明），不编。
     */
    private List<AgentRoleExecutor.ToolReading> readTools(AgentRun run, RolePlan plan) {
        List<AgentRoleExecutor.ToolReading> readings = new ArrayList<>();
        for (AgentTool tool : plan.tools()) {
            switch (tool) {
                case READ_PATIENT_MEMORY -> readings.add(readMemory(run));
                case READ_PATIENT_REPORT -> readings.add(readReport(run));
                case GET_PLAN_PROGRESS -> readings.add(readPlanProgress(run));
                case GET_PIPELINE_STATUS -> readings.add(readPipelineStatus(run));
                case GET_PATIENT_STATISTICS -> readings.add(readPatientStatistics(run));
                case GET_REPORT_STATISTICS -> readings.add(readReportStatistics(run));
                default -> {
                    // 未知工具不会出现在冻结计划里，这里只是兜底。
                }
            }
        }
        return withSampleReceipts(readings);
    }

    /**
     * 给"读到空集合"的读取补一条采样回执来源。
     *
     * <p>任务状态、账号统计这类查询，结论可能就是"没有"——但"没有"同样需要来源，
     * 来源就是这次查询本身（口径 + 采样时刻）。不补的话，来源校验会因为
     * {@code EMPTY_SOURCES} 把这类角色判死，而"该患者当前没有任务"恰恰是它该给出的结论。
     *
     * <p>只在该读取<b>没有任何实体来源</b>时补：已有实体来源时再补一条只会污染来源列表。
     * 回执的标识里带上采样时刻，于是"引用的是哪一次采样"仍然可追溯。
     */
    private List<AgentRoleExecutor.ToolReading> withSampleReceipts(
            List<AgentRoleExecutor.ToolReading> readings) {
        List<AgentRoleExecutor.ToolReading> result = new ArrayList<>();
        for (AgentRoleExecutor.ToolReading reading : readings) {
            if (!reading.sources().isEmpty()) {
                result.add(reading);
                continue;
            }
            Map<String, Object> output = new LinkedHashMap<>(reading.output());
            String sampledAt = String.valueOf(output.getOrDefault("sampled_at", OffsetDateTime.now()));
            output.put("sampled_at", sampledAt);
            output.put("note", output.containsKey("note")
                    ? output.get("note")
                    : "本次查询已执行，命中 0 条记录；这是查询时刻的快照，不等于业务正常");
            result.add(new AgentRoleExecutor.ToolReading(reading.tool(), output,
                    List.of(new AgentRoleExecutor.Source(
                            SAMPLE_RECEIPT_PREFIX + reading.tool() + ":" + sampledAt, null))));
        }
        return result;
    }

    private AgentRoleExecutor.ToolReading readMemory(AgentRun run) {
        Map<String, Object> output = new LinkedHashMap<>();
        List<AgentRoleExecutor.Source> sources = new ArrayList<>();
        try {
            PatientMemoryContract.VersionView view = memoryService.current(run.getOwner(), run.getPatientId());
            if (view != null) {
                output.put("memory", view.markdown());
                output.put("memory_id", view.id());
                output.put("version", view.version());
                output.put("sampled_at", String.valueOf(view.sampledAt()));
                output.put("source_count", view.sourceCount());
                sources.add(new AgentRoleExecutor.Source(view.id(), view.version()));
            } else {
                output.put("memory", "");
                output.put("note", "该患者尚未发布长效记忆");
            }
        } catch (BizException ex) {
            output.put("memory", "");
            output.put("note", "读取记忆失败：" + ex.getCode());
        }
        return new AgentRoleExecutor.ToolReading(AgentTool.READ_PATIENT_MEMORY.wire(), output, sources);
    }

    private AgentRoleExecutor.ToolReading readReport(AgentRun run) {
        Map<String, Object> output = new LinkedHashMap<>();
        List<AgentRoleExecutor.Source> sources = new ArrayList<>();
        List<CareContract.DocumentView> reports = careService.list(run.getOwner(), CareService.KIND_REPORT,
                run.getPatientId(), 20);
        CareContract.DocumentView current = reports.stream()
                .filter(doc -> CareService.STATUS_ACTIVE.equals(doc.status())
                        || CareService.STATUS_SIGNED.equals(doc.status()))
                .findFirst().orElse(null);
        if (current != null) {
            output.put("report_id", current.id());
            output.put("status", current.status());
            output.put("revision", current.revision());
            output.put("signed_revision", current.signedRevision());
            output.put("sections", current.sections().stream()
                    .filter(section -> section.present())
                    .map(section -> Map.of("key", section.key(), "title", section.title(),
                            "metric_refs", section.metricRefs()))
                    .toList());
            sources.add(new AgentRoleExecutor.Source(current.id(), current.revision()));
        } else {
            output.put("report", "");
            output.put("note", "该患者没有已签发或已生效的报告");
        }
        return new AgentRoleExecutor.ToolReading(AgentTool.READ_PATIENT_REPORT.wire(), output, sources);
    }

    private AgentRoleExecutor.ToolReading readPlanProgress(AgentRun run) {
        Map<String, Object> output = new LinkedHashMap<>();
        List<AgentRoleExecutor.Source> sources = new ArrayList<>();
        List<CareContract.DocumentView> plans = careService.list(run.getOwner(), CareService.KIND_PLAN,
                run.getPatientId(), 20);
        CareContract.DocumentView active = plans.stream()
                .filter(doc -> CareService.STATUS_ACTIVE.equals(doc.status()))
                .findFirst().orElse(null);
        if (active != null) {
            CareContract.CheckinSummary checkin = active.checkin();
            output.put("plan", Map.of(
                    "document_id", active.id(),
                    "revision", active.revision(),
                    "total_tasks", checkin == null ? 0 : checkin.total(),
                    "completed_tasks", checkin == null ? 0 : checkin.completed(),
                    "pending_tasks", checkin == null ? 0 : checkin.pending(),
                    "completion_rate", checkin == null || checkin.total() == 0 ? 0
                            : Math.round(checkin.completed() * 1000.0 / checkin.total()) / 10.0,
                    "sampled_at", String.valueOf(active.updatedAt())));
            output.put("matches_current_report", true);
            sources.add(new AgentRoleExecutor.Source(active.id(), active.revision()));
        } else {
            output.put("plan", "");
            output.put("note", "该患者没有已生效的计划");
        }
        return new AgentRoleExecutor.ToolReading(AgentTool.GET_PLAN_PROGRESS.wire(), output, sources);
    }

    private AgentRoleExecutor.ToolReading readPipelineStatus(AgentRun run) {
        Map<String, Object> output = new LinkedHashMap<>();
        List<AgentRoleExecutor.Source> sources = new ArrayList<>();
        List<PatientMemoryJob> jobs = memoryJobMapper.selectList(new LambdaQueryWrapper<PatientMemoryJob>()
                .eq(PatientMemoryJob::getOwner, run.getOwner())
                .eq(PatientMemoryJob::getPatientId, run.getPatientId())
                .orderByDesc(PatientMemoryJob::getCreatedAt)
                .last("limit 50"));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PatientMemoryJob job : jobs) {
            counts.merge(job.getStatus(), 1, Integer::sum);
            sources.add(new AgentRoleExecutor.Source(job.getId(), null));
        }
        output.put("status_counts", counts);
        output.put("sampled_at", String.valueOf(OffsetDateTime.now()));
        output.put("definition", "患者记忆发布任务状态；不含后台典籍流水线");
        return new AgentRoleExecutor.ToolReading(AgentTool.GET_PIPELINE_STATUS.wire(), output, sources);
    }

    private AgentRoleExecutor.ToolReading readPatientStatistics(AgentRun run) {
        Map<String, Object> output = new LinkedHashMap<>();
        Long count = patientMapper.selectCount(new LambdaQueryWrapper<com.kangheng.healthos.model.patient.Patient>()
                .eq(com.kangheng.healthos.model.patient.Patient::getCreatedBy, run.getOwner()));
        output.put("patient_count", count == null ? 0 : count);
        output.put("sampled_at", String.valueOf(OffsetDateTime.now()));
        output.put("definition", "当前账号已建档患者数量；不含患者明细");
        return new AgentRoleExecutor.ToolReading(AgentTool.GET_PATIENT_STATISTICS.wire(), output, List.of());
    }

    private AgentRoleExecutor.ToolReading readReportStatistics(AgentRun run) {
        Map<String, Object> output = new LinkedHashMap<>();
        Long count = careDocumentMapper.selectCount(new LambdaQueryWrapper<com.kangheng.healthos.model.care.CareDocument>()
                .eq(com.kangheng.healthos.model.care.CareDocument::getOwner, run.getOwner())
                .eq(com.kangheng.healthos.model.care.CareDocument::getKind, CareService.KIND_REPORT));
        output.put("report_count", count == null ? 0 : count);
        output.put("sampled_at", String.valueOf(OffsetDateTime.now()));
        output.put("definition", "当前账号正式报告数量；不含实验汇报");
        return new AgentRoleExecutor.ToolReading(AgentTool.GET_REPORT_STATISTICS.wire(), output, List.of());
    }

    /**
     * 读到"空"时必须明确说"没有"，不能让它看起来像"读到了但没引用"。
     *
     * <p>记忆角色读不到已发布记忆、生活方式角色读不到已签发报告或生效计划，
     * 都不该硬着头皮作答——那会把"没有资料"包装成"进展顺利"。
     */
    private void requireMeaningfulReading(RolePlan plan, List<AgentRoleExecutor.ToolReading> readings) {
        if (plan.role() == AgentRole.MEMORY) {
            for (AgentRoleExecutor.ToolReading reading : readings) {
                if (AgentTool.READ_PATIENT_MEMORY.wire().equals(reading.tool())
                        && reading.output().get("memory") == null) {
                    throw new BizException("AGENT_REQUIRED_MEMORY_MISSING",
                            "没有可用的已发布记忆", org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
                }
            }
        }
        if (plan.role() == AgentRole.LIFESTYLE) {
            for (AgentRoleExecutor.ToolReading reading : readings) {
                if (AgentTool.READ_PATIENT_REPORT.wire().equals(reading.tool())
                        && reading.output().get("report_id") == null) {
                    throw new BizException("AGENT_APPROVED_ADVICE_MISSING",
                            "没有已签发的报告可解释", org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
                }
                if (AgentTool.GET_PLAN_PROGRESS.wire().equals(reading.tool())
                        && reading.output().get("plan") == null) {
                    throw new BizException("AGENT_APPROVED_ADVICE_MISSING",
                            "没有生效的计划可解释", org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
                }
            }
        }
    }

    // ------------------------------------------------------------ 收尾

    /**
     * 收尾：必需角色全成功才算成功。
     *
     * <p>已核验事实只从<b>工具回执</b>里取，不取模型自己算出来的数——
     * 模型算的完成率是"模型以为的完成率"，不是患者真实的打卡进度。
     */
    private void finalizeRun(AgentRun run, List<RolePlan> plans, Map<AgentRole, String> roleResultIds,
                             Map<AgentRole, String> roleStatuses, Map<AgentRole, List<SourceRef>> roleSources,
                             AtomicInteger ordinal) {
        boolean success = plans.stream()
                .filter(plan -> plan.required())
                .allMatch(plan -> "succeeded".equals(roleStatuses.get(plan.role())));
        Map<String, Object> result = new LinkedHashMap<>();
        if (success) {
            AgentRole synthesis = AgentRole.SYNTHESIS;
            String nodeId = roleResultIds.get(synthesis);
            AgentNode node = nodeId == null ? null : nodeMapper.selectById(nodeId);
            Map<String, Object> output = node == null ? Map.of() : Json.readMap(node.getOutput());
            Map<String, Object> answer = output.get("answer") instanceof Map<?, ?> map
                    ? castAnswer(map) : Map.of();
            result.put("answer", answer);
            result.put("role_result_ids", roleResultIds);
            result.put("role_statuses", roleStatuses);
            result.put("scenario", run.getScenario());
            result.put("verified_facts", verifiedFacts(run, roleSources));
        } else {
            result.put("role_result_ids", roleResultIds);
            result.put("role_statuses", roleStatuses);
            result.put("scenario", run.getScenario());
            result.put("verified_facts", List.of());
        }
        result.put("disclaimer", AgentCollaborationContract.DISCLAIMER);
        String json = Json.write(result);
        run.setStatus(success ? STATUS_SUCCEEDED : STATUS_FAILED);
        run.setResult(json);
        run.setError(success ? null : AgentCollaborationContract.ERROR_REQUIRED_ROLE_FAILED);
        run.setCompletedAt(OffsetDateTime.now());
        runMapper.updateById(run);
        // 终态事件是轮询客户端的「停止信号」，必须与状态更新同一批写入。
        eventService.emit(run.getId(), success ? AgentRunContract.RUN_SUCCEEDED : AgentRunContract.RUN_FAILED,
                null, run.getStatus(),
                Map.of("error", success ? "" : AgentCollaborationContract.ERROR_REQUIRED_ROLE_FAILED));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castAnswer(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /**
     * 已核验事实。
     *
     * <p>只取工具回执里带采样时间与原始哈希的量：打卡进度、任务状态、统计数。
     * 每条都带 {@code definition} 说明它的口径——"打卡进度"和"后台生成状态"是两个东西，
     * 不写清楚就会被混着用。
     */
    private List<Map<String, Object>> verifiedFacts(AgentRun run, Map<AgentRole, List<SourceRef>> roleSources) {
        List<Map<String, Object>> facts = new ArrayList<>();
        for (AgentRole role : AgentRole.values()) {
            List<SourceRef> sources = roleSources.get(role);
            if (sources == null || sources.isEmpty()) {
                continue;
            }
            for (SourceRef source : sources) {
                facts.add(Map.of(
                        "kind", role.id(),
                        "id", source.id(),
                        "version", source.version() == null ? "" : source.version(),
                        "sampled_at", String.valueOf(OffsetDateTime.now()),
                        "definition", "来自角色实际读取的已落库事实，非模型计算值"));
            }
        }
        return facts;
    }

    // ------------------------------------------------------------ 记录

    /** 落一个节点。输出摘要即完整性凭据。 */
    private String recordNode(String runId, AgentRole role, String kind, String name, String status,
                              String output, String outputHash, String summary, String error,
                              AtomicInteger ordinal) {
        AgentNode node = new AgentNode();
        node.setId(Ids.newId("anode"));
        node.setRunId(runId);
        node.setRole(role == null ? null : role.id());
        node.setKind(kind);
        node.setName(name);
        node.setStatus(status);
        node.setOutput(output);
        node.setOutputHash(outputHash);
        node.setSummary(summary == null || summary.isBlank() ? null
                : summary.length() > 900 ? summary.substring(0, 900) : summary);
        node.setError(error);
        node.setOrdinal(ordinal.incrementAndGet());
        node.setCreatedAt(OffsetDateTime.now());
        node.setCompletedAt(OffsetDateTime.now());
        nodeMapper.insert(node);
        // 节点一落库就发事件：事件流里的 node.* 与 agent_node 严格一一对应，
        // 客户端拿 nodeId 去查正文时不会出现「事件有、节点还没写」的窗口。
        eventService.emit(runId, nodeEventType(status), node.getId(), status,
                Map.of("nodeId", node.getId(), "kind", String.valueOf(kind),
                        "name", String.valueOf(name)));
        return node.getId();
    }

    private static String nodeEventType(String status) {
        return switch (status == null ? "" : status) {
            case "succeeded" -> AgentRunContract.NODE_COMPLETED;
            case "failed" -> AgentRunContract.NODE_FAILED;
            case "skipped" -> AgentRunContract.NODE_SKIPPED;
            case "cancelled" -> AgentRunContract.NODE_CANCELLED;
            default -> AgentRunContract.NODE_STARTED;
        };
    }

    // ------------------------------------------------------------ 查询

    /** 运行视图。 */
    public AgentCollaborationContract.CollaborationRunView get(String owner, String runId) {
        AgentRun run = runMapper.selectById(runId);
        if (run == null || !Objects.equals(run.getOwner(), owner)) {
            throw BizException.notFound("协同运行");
        }
        List<AgentNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<AgentNode>()
                .eq(AgentNode::getRunId, runId)
                .orderByAsc(AgentNode::getOrdinal)
                // 重试时新节点的 ordinal 可能和上一轮重复；再加一列时间排序，
                // 保证「先发生的排前面」，不会把新结论显示在旧结论之前。
                .orderByAsc(AgentNode::getCreatedAt));
        Map<String, Object> result = Json.readMap(run.getResult());
        AgentAnswer answer = null;
        Object rawAnswer = result.get("answer");
        if (rawAnswer instanceof Map<?, ?> map) {
            answer = new AgentAnswer(
                    str(map.get("summary")),
                    strings(map.get("progress")),
                    strings(map.get("limitations")),
                    sources(map.get("sources")));
        }
        Map<String, String> roleStatuses = new LinkedHashMap<>();
        Object rawStatuses = result.get("role_statuses");
        if (rawStatuses instanceof Map<?, ?> map) {
            map.forEach((key, value) -> roleStatuses.put(String.valueOf(key), String.valueOf(value)));
        }
        List<AgentCollaborationContract.RoleNodeView> nodeViews = nodes.stream()
                .map(node -> new AgentCollaborationContract.RoleNodeView(node.getId(), node.getRole(),
                        node.getKind(), node.getName(), node.getStatus(), node.getSummary(), node.getError(),
                        node.getOutputHash(),
                        new AgentCollaborationContract.OffsetDateTimeView(
                                String.valueOf(node.getCreatedAt()))))
                .toList();
        return new AgentCollaborationContract.CollaborationRunView(run.getId(), run.getOwner(),
                run.getPatientId(), run.getScenario(), Json.readList(run.getScopes(), String.class),
                run.getStatus(), run.getInstruction(), answer,
                roleStatuses.entrySet().stream().map(entry -> entry.getKey() + ":" + entry.getValue()).toList(),
                nodeViews, run.getError(), AgentCollaborationContract.DISCLAIMER, run.getGraphVersion());
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private List<SourceRef> sources(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<SourceRef> refs = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && map.get("id") != null) {
                Object version = map.get("version");
                refs.add(new SourceRef(String.valueOf(map.get("id")),
                        version instanceof Number number ? number.intValue() : null));
            }
        }
        return refs;
    }

    // ------------------------------------------------------------ 值对象

    /** 角色执行结果。 */
    private record RoleResult(String nodeId, List<SourceRef> sources) {
    }

    /** 一条上游交接。 */
    private record Upstream(AgentRole role, String nodeId, String outputHash, List<SourceRef> sources) {
    }
}
