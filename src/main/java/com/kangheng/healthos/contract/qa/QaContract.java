package com.kangheng.healthos.contract.qa;

import java.util.List;

import com.kangheng.healthos.model.qa.Citation;

/**
 * 问答域契约。引用里带上字符区间与页码，前端可以直接定位到原文位置。
 *
 * <p>{@code mode} 两种取值：
 * <ul>
 *   <li>{@code single}（默认）——单路原文证据问答，与旧版行为完全一致；</li>
 *   <li>{@code parallel}——原文 / 图谱 / 指标三路并行，汇总后返回，并带每路明细。</li>
 * </ul>
 * 两模式共用同一套落库与引用结构，历史消息回读不区分模式。
 */
public final class QaContract {

    private QaContract() {
    }

    /**
     * @param patientId 仅并行模式的指标路使用；不传则该路 SKIPPED
     */
    public record AskRequest(String documentId, String conversationId, String question,
                             String mode, String patientId) {
    }

    /** 单路分支明细。{@code status}: SUCCEEDED / INSUFFICIENT_EVIDENCE / SKIPPED / FAILED。 */
    public record BranchView(String key, String label, String status, String basis,
                             String answer, String errorCode, long elapsedMs, String note,
                             List<Citation> citations) {
    }

    /**
     * @param errorCode 仅在不完整（QA_BRANCHES_INCOMPLETE 等）或全无依据时给出，成功时为 null
     * @param branches  并行模式的各路明细；单路模式为空列表
     */
    public record AskResponse(String conversationId, String userMessageId, String assistantMessageId,
                              String answer, String status, List<Citation> citations,
                              String mode, String errorCode, List<BranchView> branches) {
    }

    public record MessageView(String id, String role, String content, String status,
                              List<Citation> citations, List<BranchView> branches,
                              java.time.OffsetDateTime createdAt) {
    }
}
