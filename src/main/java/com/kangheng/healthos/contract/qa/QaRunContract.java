package com.kangheng.healthos.contract.qa;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 问答运行（run）的对外契约。
 *
 * <p>与 {@link QaContract} 的分工：{@code QaContract} 是"一次问答的结果"，
 * 这里管的是"一次问答的生命周期"——提交、节点明细、事件流、重试、取消。
 */
public final class QaRunContract {

    private QaRunContract() {
    }

    /**
     * 提交一次问答。
     *
     * @param idempotencyKey 幂等键。同一个会话内重复提交同一问题返回同一个 run；
     *                        键相同但问题不同 → 409，不静默复用
     * @param expectedVersion 仅用于取消/重试：并发操作时后到的那次明确失败
     */
    public record SubmitRequest(String documentId, String conversationId, String question,
                                String mode, String patientId, String idempotencyKey,
                                Integer expectedVersion) {
    }

    /** 受理结果。{@code statusUrl} 是这次运行的查询地址。 */
    public record AcceptedResponse(String runId, String conversationId, String documentId,
                                    String status, Integer version, String statusUrl) {
    }

    /** 节点明细。{@code status}: PENDING / RUNNING / SUCCEEDED / FAILED / SKIPPED / CANCELLED。 */
    public record NodeView(String nodeKey, String label, String status, Integer attemptCount,
                           Integer autoAttemptLimit, String errorCode, String errorMessage,
                           OffsetDateTime startedAt, OffsetDateTime completedAt) {
    }

    /** 一次尝试的留痕。 */
    public record AttemptView(String nodeKey, Integer attemptNo, String status,
                              String errorCode, String errorMessage,
                              OffsetDateTime startedAt, OffsetDateTime completedAt) {
    }

    /**
     * 运行详情。
     *
     * @param lastEventSeq 事件序号水位线；SSE 重连带 {@code afterSeq=lastEventSeq}
     */
    public record RunDetail(String runId, String conversationId, String documentId, String question,
                            String mode, String status, Integer version, String answer,
                            String errorCode, String errorMessage, Integer lastEventSeq,
                            List<NodeView> nodes, List<AttemptView> attempts,
                            OffsetDateTime createdAt, OffsetDateTime startedAt,
                            OffsetDateTime completedAt) {
    }

    /** 单条事件。{@code payload} 是事件负载（JSON 对象）。 */
    public record EventView(Integer seq, String type, Map<String, Object> payload,
                            OffsetDateTime createdAt) {
    }

    /**
     * 事件分页。
     *
     * @param nextSeq 下一批的起点；客户端拿它继续拉
     * @param hasMore 是否还有后续事件
     */
    public record EventPage(String runId, List<EventView> items, Integer nextSeq,
                            boolean hasMore, String status) {
    }

    /**
     * 重试失败节点。
     *
     * @param nodeKeys 要重跑的节点；为空表示"把所有失败节点都重跑一遍"
     */
    public record RetryRequest(List<String> nodeKeys, Integer expectedVersion, String idempotencyKey) {
    }

    /** 取消。{@code expectedVersion} 用于并发冲突检测。 */
    public record CancelRequest(Integer expectedVersion) {
    }
}
