package com.kangheng.healthos.contract.patient;

import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;

/**
 * 记忆 Agent 摘录写入契约。
 *
 * <p>对齐原工程 {@code memory_agent_service.py} 的收敛版：Java 侧不启 STDIO 子进程，
 * 候选由本服务在进程内生成，状态先落 PENDING，<b>不自动写记忆版本</b>——
 * 对齐原工程"显式授权 + 候选由独立 worker 发布"的分工。
 *
 * <p>{@code observations} 恒为数组，每项 {@code {entryIndex, quote}}；quote 必须是对应
 * 随访 {@code entries[entryIndex].content} 的连续原文摘录（用 String.contains 逐字校验），
 * 且 entryIndex 集合必须等于 0..entries.size()-1（覆盖随访全部分类）。
 */
public final class MemoryAgentNoteContract {

    private MemoryAgentNoteContract() {
    }

    /** 一条逐字摘录：entryIndex 指向随访 entries 下标，quote 是 content 的连续原文片段。 */
    public record Observation(int entryIndex, String quote) {
    }

    /** 随访原文条目：分类 + 患者自述原文（不改写、不摘要）。 */
    public record AgentEntry(String category, String content) {
    }

    /**
     * 提交候选。为降低耦合：优先 {@code followupId}（从既有 patient_followup 读 entries）；
     * 没有 followupId 时收 {@code entries} 并冻结进 note 的来源快照 JSON（见 Service 注释）。
     */
    public record AgentFollowupRequest(@NotBlank String operationId, String instruction,
                                       String followupId, List<AgentEntry> entries) {
    }

    /** 发布请求：必须带生成时返回的 candidateHash，防止基于过期候选发布。 */
    public record PublishRequest(@NotBlank String expectedCandidateHash) {
    }

    /** 草稿/发布后的视图。 */
    public record NoteView(String noteId, String patientId, String status, String candidateHash,
                           List<Observation> observations, OffsetDateTime createdAt) {
    }

    /** 列表响应。 */
    public record NoteListView(List<NoteView> items) {
    }
}
