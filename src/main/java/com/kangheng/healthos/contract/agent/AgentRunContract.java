package com.kangheng.healthos.contract.agent;

import java.util.List;

import jakarta.validation.constraints.Min;

/**
 * Agent Run 事件流与控制面的冻结契约。
 *
 * <p>对齐原工程 {@code agent_run_api.py}：<b>轮询而不是 SSE 作为主通道</b>
 * （SSE 只是同一份事件的流式包装）。事件响应里只有 {@code seq/type/nodeId/status/createdAt}，
 * <b>不含 detail</b>——业务正文必须走节点详情端点，那里才有权限与保留期校验。
 */
public final class AgentRunContract {

    private AgentRunContract() {
    }

    // ------------------------------------------------------------ 事件类型

    public static final String RUN_QUEUED = "run.queued";
    public static final String RUN_STARTED = "run.started";
    public static final String RUN_SUCCEEDED = "run.succeeded";
    public static final String RUN_FAILED = "run.failed";
    public static final String RUN_CANCELLED = "run.cancelled";
    public static final String RUN_RETRY_REQUESTED = "run.retry_requested";
    public static final String NODE_STARTED = "node.started";
    public static final String NODE_COMPLETED = "node.completed";
    public static final String NODE_FAILED = "node.failed";
    public static final String NODE_SKIPPED = "node.skipped";
    public static final String NODE_CANCELLED = "node.cancelled";
    public static final String NODE_PAYLOAD_READ = "node.payload_read";

    // ------------------------------------------------------------ 错误码

    public static final String ERROR_VERSION_CONFLICT = "AGENT_VERSION_CONFLICT";
    public static final String ERROR_RUN_STATE = "AGENT_RUN_STATE";
    public static final String ERROR_PAYLOAD_FORBIDDEN = "TRACE_PAYLOAD_FORBIDDEN";
    public static final String ERROR_PAYLOAD_EXPIRED = "TRACE_EXPIRED";
    public static final String ERROR_CURSOR_INVALID = "AGENT_CURSOR_INVALID";
    public static final String ERROR_DEADLINE = "AGENT_RUN_DEADLINE_EXCEEDED";

    /** 节点正文保留期（对齐原工程 trace_retention_days）。 */
    public static final int PAYLOAD_RETENTION_DAYS = 7;

    // ------------------------------------------------------------ DTO

    /** 单条事件视图。detail 有意不暴露。 */
    public record EventView(int seq, String type, String nodeId, String status, String createdAt) {
    }

    /**
     * 增量事件页。
     *
     * @param nextSeq 本页最后一条 seq；无数据时回显 afterSeq
     * @param eventSeq run 当前总序号，客户端据此判断自己落后多少
     * @param status run 状态，客户端据此决定是否停止轮询
     */
    public record EventPage(String runId, List<EventView> items, int nextSeq,
                            boolean hasMore, int eventSeq, String status) {
    }

    /** 运行列表项。 */
    public record RunSummaryView(String id, String patientId, String scenario, String status,
                                 int eventSeq, int version, String createdAt) {
    }

    /** 键集分页：nextCursor 是本页最后一条 id，null 表示没有更多。 */
    public record RunPage(List<RunSummaryView> items, String nextCursor) {
    }

    /** 控制命令的返回。 */
    public record ControlView(String runId, String status, int version, int eventSeq,
                              String error, boolean idempotent) {
    }

    /** 取消。expectedVersion 可空（兼容旧客户端），给了就做乐观锁校验。 */
    public record CancelRequest(@Min(1) Integer expectedVersion) {
    }

    /** 重试。只对 FAILED 有效；QUEUED/RUNNING 视为「响应丢失对账」幂等返回。 */
    public record RetryRequest(@Min(1) Integer expectedVersion) {
    }

    /**
     * 节点正文视图。
     *
     * <p>{@code mode=redacted} 时 input/output 只给字段名 + 占位说明；
     * {@code mode=full} 需要全局读权限且在保留期内，并落一条审计事件。
     */
    public record NodePayloadView(String nodeId, String mode, boolean canReadFull,
                                  String payloadStatus, Object input, Object output,
                                  String expiresAt) {
    }
}
