package com.kangheng.healthos.contract.patient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Size;

/**
 * 患者长效记忆契约。
 *
 * <p>对应原工程 {@code memory_api} / {@code memory_jobs} 一族。四条语义写在这里，
 * 免得调用方凭接口名猜：
 * <ul>
 *   <li>版本**不可变**，"刷新"是新增一版并移动指针，不是改现有版本；</li>
 *   <li>事实只从<b>冻结来源</b>取（已落库的修订快照 / 已签发的照护修订 / 随访原文），
 *       每个版本带 {@code contentHash} 与来源清单，可离线核对；</li>
 *   <li>{@code operationId} 由调用方生成，同患者重复提交返回同一个 job（幂等）；</li>
 *   <li>随访 {@code entries} 是<b>患者自述原文</b>，不是已核验的医学事实，
 *       写进记忆不会改变它的性质。</li>
 * </ul>
 */
public final class PatientMemoryContract {

    private PatientMemoryContract() {
    }

    /**
     * 提交一次记忆操作。
     *
     * @param operationId      幂等键，{@code [A-Za-z0-9_-]{8,64}}
     * @param expectedMemoryId 提交时看到的当前版本 ID。传 {@code null} 表示
     *                         "客户端认为该患者还没有记忆"——若服务端已有版本，直接 409，
     *                         不做静默覆盖。这样无需额外标志位就能表达"显式 null"。
     */
    public record MemoryRequest(String operationId, String expectedMemoryId) {
    }

    /** 一条随访反馈：分类 + 原文。分类同一次提交内不可重复。 */
    public record FeedbackEntry(String category, @Size(max = 500) String content) {
    }

    /** 随访提交。{@code occurredAt} 必须带时区，且不能是未来时间（允许 5 分钟时钟误差）。 */
    public record FollowupRequest(String operationId, String expectedMemoryId,
                                  OffsetDateTime occurredAt, List<FeedbackEntry> entries) {
    }

    /** 版本视图。{@code data}/{@code sources} 是采样时的冻结快照。 */
    public record VersionView(String id, String patientId, int version, String schemaVersion,
                              String generationMode, String contentHash, String sourceHash,
                              int sourceCount, String markdown, Map<String, Object> data,
                              List<Map<String, Object>> sources,
                              OffsetDateTime sampledAt, OffsetDateTime createdAt) {
    }

    /** 版本列表项：不返回 markdown / data，避免把整份记忆当成列表字段拉回来。 */
    public record VersionSummary(String id, int version, String contentHash, String sourceHash,
                                 int sourceCount, OffsetDateTime sampledAt, OffsetDateTime createdAt) {
    }

    /** 随访视图。{@code entries} 原样返回，不改写。 */
    public record FollowupView(String id, String operationId, int requestSequence,
                               OffsetDateTime occurredAt, OffsetDateTime createdAt,
                               String actor, String origin, List<FeedbackEntry> entries) {
    }

    /** 任务视图。{@code status}: QUEUED / RUNNING / SUCCEEDED / FAILED / CANCELLED。 */
    public record JobView(String id, String patientId, String operationId, int requestSequence,
                          String kind, String feedbackId, String expectedMemoryId,
                          String status, int attemptCount, String resultMemoryId, String error,
                          OffsetDateTime createdAt, OffsetDateTime startedAt,
                          OffsetDateTime completedAt) {
    }

    /** 202 响应：任务已受理，版本由后台发布。 */
    public record Accepted(JobView job) {
    }
}
