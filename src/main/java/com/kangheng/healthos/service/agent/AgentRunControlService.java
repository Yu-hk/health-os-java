package com.kangheng.healthos.service.agent;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import com.kangheng.healthos.common.AccessControl;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.contract.agent.AgentRunContract;
import com.kangheng.healthos.mapper.agent.AgentNodeMapper;
import com.kangheng.healthos.mapper.agent.AgentRunMapper;
import com.kangheng.healthos.model.agent.AgentNode;
import com.kangheng.healthos.model.agent.AgentRun;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent Run 控制面：取消、重试、列表、节点正文。
 *
 * <p>对齐原工程 {@code agent_run_api.py} 的三条硬语义：
 * <ol>
 *   <li><b>取消幂等</b>：已经终态的 run 原样返回，不报错、不重复写事件；</li>
 *   <li><b>重试只对失败态有效</b>：成功/取消不可逆；排队/执行中视为「响应丢失对账」幂等返回；</li>
 *   <li><b>版本乐观锁</b>：页面停在旧状态时的一次点击，不能把新一轮执行取消掉。</li>
 * </ol>
 *
 * <p>原工程没有 pause/resume/replay，这里也不加——状态越多，越难说清
 * 「一次运行到底有没有真的跑完」。
 */
@Service
public class AgentRunControlService {

    /** 执行时限，对齐原工程 RUN_TIMEOUT=180s。 */
    public static final int RUN_TIMEOUT_SECONDS = 180;

    private static final String STATUS_QUEUED = "QUEUED";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String ERROR_CANCELLED = "AGENT_CANCELLED";

    /** 节点正文里即使出现这些键也要抹掉，避免正文端点变成凭据泄露口。 */
    private static final Set<String> SECRET_KEYS = Set.of(
            "api_key", "authorization", "password", "access_token", "id_card", "phone",
            "session_secret", "base_url", "reasoning", "thinking");

    private final AgentRunMapper runMapper;
    private final AgentNodeMapper nodeMapper;
    private final AgentRunEventService eventService;
    private final OutboxServiceAdapter outbox;
    private final AccessControl accessControl;

    public AgentRunControlService(AgentRunMapper runMapper,
                                  AgentNodeMapper nodeMapper,
                                  AgentRunEventService eventService,
                                  com.kangheng.healthos.service.outbox.OutboxService outboxService,
                                  AccessControl accessControl) {
        this.runMapper = runMapper;
        this.nodeMapper = nodeMapper;
        this.eventService = eventService;
        this.outbox = new OutboxServiceAdapter(outboxService);
        this.accessControl = accessControl;
    }

    // ------------------------------------------------------------ 取消

    /**
     * 取消一次运行。
     *
     * <p>取消要同时做两件事：把 run 置终态，把还在 running 的节点置 cancelled。
     * 只改 run 不改节点，事件流里就会留下一批「永远在跑」的节点。
     */
    @Transactional
    public AgentRunContract.ControlView cancel(String owner, String runId, Integer expectedVersion) {
        AgentRun run = requireOwned(owner, runId);
        requireVersion(run, expectedVersion);
        if (!STATUS_QUEUED.equals(run.getStatus()) && !STATUS_RUNNING.equals(run.getStatus())) {
            return view(run, true);
        }

        List<AgentNode> running = nodeMapper.selectList(new LambdaQueryWrapper<AgentNode>()
                .eq(AgentNode::getRunId, runId)
                .eq(AgentNode::getStatus, "running"));
        if (running == null) {
            running = List.of();
        }

        int nextVersion = AgentRunEventService.versionOf(run) + 1;
        OffsetDateTime completedAt = OffsetDateTime.now();
        // 显式 update：version 与 event_seq 都被标了 updateStrategy=NEVER，
        // 用 updateById 无法推进版本号——控制面的乐观锁只能在这里改。
        runMapper.update(null, Wrappers.<AgentRun>update()
                .eq("id", runId)
                .set("status", STATUS_CANCELLED)
                .set("version", nextVersion)
                .set("error", ERROR_CANCELLED)
                .set("completed_at", completedAt));
        run.setStatus(STATUS_CANCELLED);
        run.setError(ERROR_CANCELLED);
        run.setVersion(nextVersion);
        run.setCompletedAt(completedAt);

        int cancelled = 0;
        for (AgentNode node : running) {
            // 再判一次状态：SQL 条件与内存对象可能因并发写入不一致，
            // 已成功的节点绝不能被取消掉——它们是重试时的断点续跑凭据。
            if (!"running".equals(node.getStatus())) {
                continue;
            }
            node.setStatus("cancelled");
            node.setCompletedAt(OffsetDateTime.now());
            nodeMapper.updateById(node);
            cancelled++;
            eventService.emit(runId, AgentRunContract.NODE_CANCELLED, node.getId(), "cancelled",
                    Map.of("nodeId", node.getId(), "kind", String.valueOf(node.getKind())));
        }
        eventService.emit(runId, AgentRunContract.RUN_CANCELLED, null, STATUS_CANCELLED,
                Map.of("cancelledNodes", cancelled));
        return view(run, false);
    }

    // ------------------------------------------------------------ 重试

    /**
     * 重试一次失败的运行。
     *
     * <p><b>不重跑已成功的角色</b>：由 {@code AgentCollaborationService.execute} 读
     * {@code agent_node} 里已 succeeded 的角色节点做断点续跑。这里只负责把状态
     * 退回 QUEUED 并重新派发，不碰节点历史——删掉历史就等于毁掉交接凭据。
     */
    @Transactional
    public AgentRunContract.ControlView retry(String owner, String runId, Integer expectedVersion) {
        AgentRun run = requireOwned(owner, runId);
        requireVersion(run, expectedVersion);
        if (STATUS_QUEUED.equals(run.getStatus()) || STATUS_RUNNING.equals(run.getStatus())) {
            // 首次提交的响应丢了、或者 worker 已经在跑：重放同一请求不该再排一次队。
            return view(run, true);
        }
        if (!STATUS_FAILED.equals(run.getStatus())) {
            throw new BizException(AgentRunContract.ERROR_RUN_STATE,
                    "只有失败的运行可以重试；成功与取消均不可逆",
                    HttpStatus.CONFLICT);
        }

        // updateById 默认跳过 null 字段，清 error/result/completedAt 必须显式 set null，
        // 否则重试后会残留上一次的失败原因，get() 视图里成功与失败同时出现。
        int previousVersion = AgentRunEventService.versionOf(run);
        OffsetDateTime deadline = OffsetDateTime.now().plusSeconds(RUN_TIMEOUT_SECONDS);
        // 用列名字符串而不是 lambda：显式 set null 是这里的关键（清掉上一次的失败痕迹），
        // lambda 版本依赖 MyBatis-Plus 的实体缓存，脱离 Spring 上下文的纯单测里会炸。
        runMapper.update(null, Wrappers.<AgentRun>update()
                .eq("id", runId)
                .set("status", STATUS_QUEUED)
                .set("version", previousVersion + 1)
                .set("error", null)
                .set("result", null)
                .set("started_at", null)
                .set("completed_at", null)
                .set("deadline_at", deadline));

        // 同步刷新内存对象，让返回值与库里的状态一致；不额外查一次库，
        // 免得刚提交就读到别的连接尚未可见的旧值。
        run.setStatus(STATUS_QUEUED);
        run.setVersion(previousVersion + 1);
        run.setError(null);
        run.setResult(null);
        run.setStartedAt(null);
        run.setCompletedAt(null);
        run.setDeadlineAt(deadline);

        eventService.emit(runId, AgentRunContract.RUN_RETRY_REQUESTED, null, STATUS_QUEUED,
                Map.of("previousVersion", previousVersion));
        outbox.append(runId);
        return view(run, false);
    }

    // ------------------------------------------------------------ 列表

    /**
     * 运行列表（键集分页）。
     *
     * <p>用 {@code (created_at, id)} 双列排序而不是 OFFSET：协同是异步的，
     * 翻页期间不断有新行插入，OFFSET 会漏行或重复行。
     */
    public AgentRunContract.RunPage page(String owner, String patientId, int limit, String beforeId) {
        int safeLimit = Math.min(100, Math.max(1, limit));
        LambdaQueryWrapper<AgentRun> query = Wrappers.<AgentRun>lambdaQuery()
                .eq(AgentRun::getOwner, owner);
        if (patientId != null && !patientId.isBlank()) {
            query.eq(AgentRun::getPatientId, patientId);
        }
        if (beforeId != null && !beforeId.isBlank()) {
            AgentRun cursor = eventService.owned(owner, beforeId);
            OffsetDateTime createdAt = cursor.getCreatedAt();
            if (createdAt == null) {
                throw new BizException(AgentRunContract.ERROR_CURSOR_INVALID,
                        "游标行缺少创建时间，无法继续翻页", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            query.and(w -> w.lt(AgentRun::getCreatedAt, createdAt)
                    .or(x -> x.eq(AgentRun::getCreatedAt, createdAt)
                            .lt(AgentRun::getId, cursor.getId())));
        }
        query.orderByDesc(AgentRun::getCreatedAt).orderByDesc(AgentRun::getId)
                .last("LIMIT " + (safeLimit + 1));
        List<AgentRun> rows = runMapper.selectList(query);
        if (rows == null) {
            rows = List.of();
        }
        boolean more = rows.size() > safeLimit;
        List<AgentRun> page = more ? new ArrayList<>(rows.subList(0, safeLimit)) : rows;
        List<AgentRunContract.RunSummaryView> items = page.stream()
                .map(row -> new AgentRunContract.RunSummaryView(row.getId(), row.getPatientId(),
                        row.getScenario(), row.getStatus(), AgentRunEventService.seqOf(row),
                        AgentRunEventService.versionOf(row), String.valueOf(row.getCreatedAt())))
                .toList();
        return new AgentRunContract.RunPage(items,
                more ? page.get(page.size() - 1).getId() : null);
    }

    // ------------------------------------------------------------ 节点正文

    /**
     * 节点正文：默认脱敏，full 需要全局读权限且在保留期内。
     *
     * <p>原工程把 full 读取单独落审计（TracePayloadRead）；这里落成一条
     * {@code node.payload_read} 事件——同一份事件流既是进度通道也是审计通道，
     * 不必再开一张只写不读的审计表。
     */
    public AgentRunContract.NodePayloadView nodePayload(String owner, String runId,
                                                        String nodeId, String mode) {
        eventService.owned(owner, runId);
        AgentNode node = nodeMapper.selectById(nodeId);
        if (node == null || !runId.equals(node.getRunId())) {
            throw BizException.notFound("运行节点");
        }
        OffsetDateTime createdAt = node.getCreatedAt() == null
                ? OffsetDateTime.now() : node.getCreatedAt();
        OffsetDateTime expiresAt = createdAt.plusDays(AgentRunContract.PAYLOAD_RETENTION_DAYS);
        boolean expired = OffsetDateTime.now().isAfter(expiresAt);
        boolean full = "full".equalsIgnoreCase(mode);
        boolean canReadFull = accessControl.isTraceGlobalReader(owner) || accessControl.isAdmin(owner);

        if (full && !canReadFull) {
            throw new BizException(AgentRunContract.ERROR_PAYLOAD_FORBIDDEN,
                    "读取节点正文需要全局读权限", HttpStatus.FORBIDDEN);
        }
        if (full && expired) {
            throw new BizException(AgentRunContract.ERROR_PAYLOAD_EXPIRED,
                    "节点正文已超过保留期", HttpStatus.GONE);
        }

        Map<String, Object> output = Json.readMap(node.getOutput());
        Object payload;
        if (full) {
            payload = scrub(output);
            eventService.emit(runId, AgentRunContract.NODE_PAYLOAD_READ, nodeId, node.getStatus(),
                    Map.of("nodeId", nodeId, "mode", "full"));
        } else {
            payload = Map.of("fields", new ArrayList<>(new LinkedHashSet<>(output.keySet())),
                    "content", "正文按权限隐藏");
        }
        return new AgentRunContract.NodePayloadView(nodeId, full ? "full" : "redacted",
                canReadFull, expired ? "expired" : "available",
                Map.of("fields", List.of("output"), "content", "节点输入只在本运行内使用"),
                payload, expiresAt.toString());
    }

    // ------------------------------------------------------------ 内部

    private AgentRun requireOwned(String owner, String runId) {
        AgentRun run = eventService.owned(owner, runId);
        if (run == null) {
            // 归属校验的实现理论上不会返回 null；这里兜住自定义实现，
            // 避免一个空值把控制命令变成 500 而不是明确的 404。
            throw BizException.notFound("协同运行");
        }
        return run;
    }

    private void requireVersion(AgentRun run, Integer expectedVersion) {
        if (expectedVersion == null) {
            return;
        }
        if (!expectedVersion.equals(AgentRunEventService.versionOf(run))) {
            throw new BizException(AgentRunContract.ERROR_VERSION_CONFLICT,
                    "运行已被其他操作更新，请刷新后重试",
                    HttpStatus.CONFLICT);
        }
    }

    private AgentRunContract.ControlView view(AgentRun run, boolean idempotent) {
        return new AgentRunContract.ControlView(run.getId(), run.getStatus(),
                AgentRunEventService.versionOf(run), AgentRunEventService.seqOf(run),
                run.getError(), idempotent);
    }

    /** 递归脱敏：键命中黑名单直接换成占位，不把值带出去。 */
    private static Map<String, Object> scrub(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (SECRET_KEYS.contains(entry.getKey().toLowerCase())) {
                result.put(entry.getKey(), "<hidden>");
            } else if (entry.getValue() instanceof Map<?, ?> nested) {
                Map<String, Object> inner = new LinkedHashMap<>();
                nested.forEach((key, value) -> inner.put(String.valueOf(key), value));
                result.put(entry.getKey(), scrub(inner));
            } else {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /**
     * 把 Outbox 派发收在一个窄接口后面：控制面只关心「重新排一次队」，
     * 不需要知道事件类型、聚合类型这些编排细节。
     */
    private static final class OutboxServiceAdapter {

        private final com.kangheng.healthos.service.outbox.OutboxService delegate;

        private OutboxServiceAdapter(com.kangheng.healthos.service.outbox.OutboxService delegate) {
            this.delegate = delegate;
        }

        void append(String runId) {
            delegate.append("agent_run", runId, AgentCollaborationService.EVENT_EXECUTE,
                    Map.of("runId", runId));
        }
    }
}
