package com.kangheng.healthos.contract.patient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class PatientContract {

    private PatientContract() {
    }

    /** 建档。默认建成 ACTIVE；{@code draft=true} 时建成 DRAFT（可先存不完整的档案）。 */
    public record CreatePatientRequest(@NotBlank String name, @NotBlank String gender,
                                       LocalDate birthDate, String phone, String externalRef,
                                       Boolean draft) {
    }

    /**
     * 编辑。所有字段可空，只有非空字段会被改动（真·部分更新）。
     *
     * <p>{@code expectedRevision} 必填：不带版本号的写请求直接拒绝，
     * 否则"读-改-写"之间别人的修改会被无声覆盖。
     */
    public record UpdatePatientRequest(@NotNull Integer expectedRevision, String name, String gender,
                                       LocalDate birthDate, String phone, String externalRef,
                                       String reason) {
    }

    /** 状态流转（确认 / 归档 / 恢复）。 */
    public record TransitionRequest(@NotNull Integer expectedRevision, String reason) {
    }

    public record MetricInput(@NotBlank String code, @NotBlank String name, @NotNull BigDecimal value,
                              String unit, BigDecimal referenceLow, BigDecimal referenceHigh,
                              LocalDate measuredAt) {
    }

    public record MetricView(String id, String code, String name, BigDecimal value, String unit,
                             BigDecimal referenceLow, BigDecimal referenceHigh, String status,
                             LocalDate measuredAt) {
    }

    public record PatientView(String id, String name, String gender, LocalDate birthDate,
                              String phone, String riskLevel, String status, Integer revision,
                              String externalRef, String createdBy, OffsetDateTime createdAt,
                              OffsetDateTime deletedAt, OffsetDateTime archivedAt) {
    }

    /** abnormal 按状态计数，便于前端直接展示"3 项偏高 / 1 项偏低"。 */
    public record PatientDetail(PatientView patient, List<MetricView> metrics,
                                Map<String, Long> abnormal) {
    }

    public record RevisionView(String id, int revision, String actor, String reason, String sha256,
                               OffsetDateTime createdAt) {
    }

    public record ObjectView(String id, String patientId, String kind, String filename,
                             String contentType, long sizeBytes, String sha256, String status,
                             OffsetDateTime createdAt) {
    }

    public record BatchSubmitResponse(String batchId, String status, int totalRows, boolean reused) {
    }

    public record BatchRowView(int rowNo, String externalRef, String status, String patientId,
                               String errorCode, String errorMessage) {
    }

    public record BatchView(String id, String filename, String format, String status, int totalRows,
                            int succeededRows, int failedRows, String createdBy, String errorMessage,
                            OffsetDateTime createdAt, OffsetDateTime updatedAt,
                            List<BatchRowView> rows) {
    }

    public record DeletionPreview(String taskId, String patientId, String status, String previewToken,
                                  int resourceCount, Map<String, Long> byKind, String reason) {
    }

    /**
     * 预览请求。{@code reason} 走请求体而不是 query string——删除理由通常是
     * 「患者家属申请删除」这类中文，放 URL 里调用方必须自己 encode，漏一次就是一堆乱码。
     */
    public record DeletionPreviewRequest(String reason) {
    }

    public record DeletionConfirmRequest(@NotBlank String previewToken,
                                         @NotNull Integer expectedRevision) {
    }

    public record DeletionResourceView(String kind, String key, String scope, boolean erased) {
    }

    public record DeletionTaskView(String id, String patientId, String status, String requestedBy,
                                   String reason, int resourceCount, String ledgerKey,
                                   String ledgerSha256, String errorMessage,
                                   OffsetDateTime createdAt, OffsetDateTime confirmedAt,
                                   OffsetDateTime completedAt,
                                   List<DeletionResourceView> resources) {
    }

    /** @param verified 台账内容与登记时的 sha256 是否一致；不一致说明台账被改过。 */
    public record LedgerView(String taskId, String sha256, boolean verified, Object content) {
    }

    public record OrphanScan(String prefix, int totalKeys, int referencedKeys, int orphanCount,
                             List<String> orphans, boolean truncated) {
    }

    public record OrphanPurgeRequest(@NotNull List<String> keys) {
    }

    public record OrphanPurgeResult(int requested, int deleted, List<String> skipped) {
    }

    // ------------------------------------------------------------ 资料录入

    /** 录入提交结果。{@code reused=true} 表示这份资料之前传过，直接复用原录入单。 */
    public record IntakeSubmitResponse(String examId, String status, boolean reused) {
    }

    /** 一个分片的抽取结果。{@code fields} 是基础字段，{@code metrics} 是指标。 */
    public record IntakePartView(String partKey, String label, String status,
                                  Map<String, Object> fields, List<Map<String, Object>> metrics,
                                  String errorCode, String errorMessage) {
    }

    /** 录入单详情。{@code aggregate} 是所有分片汇总后的字段与指标。 */
    public record IntakeView(String id, String owner, String patientId, String sourceFilename,
                             String sourceSha256, long sourceSize, String mediaType, String status,
                             String basicReviewStatus, String clinicalReviewStatus,
                             String basicReviewNote, String clinicalReviewNote,
                             String extractor, int revision, String errorCode, String errorMessage,
                             OffsetDateTime createdAt, OffsetDateTime updatedAt,
                             Map<String, Object> aggregate, List<IntakePartView> parts) {
    }

    /**
     * 核查请求。
     *
     * <p>{@code basicDecision} 与 {@code clinicalDecision} 取 {@code CONFIRMED} / {@code REJECTED}，
     * 传 {@code null} 表示这一道本次不表态。驳回必须带意见（{@code basicNote} / {@code clinicalNote}），
     * 否则驳回等于没说明白。
     */
    public record IntakeReviewRequest(String basicDecision, String basicNote,
                                      String clinicalDecision, String clinicalNote) {
    }

    /**
     * 确认录入：两道核查都通过后生成正式档案。
     *
     * <p>{@code name}/{@code gender}/{@code birthDate}/{@code phone} 是<b>人工补录的字段</b>。
     * 它们不是可选的装饰——OCR 与规则抽取都会有抽不到的时候
     * （扫描件字迹模糊、姓名只有单字），而抽取器<b>不许猜值</b>，
     * 所以缺的那几个字必须由核查的人补上，否则这份资料永远停在"抽不到姓名"，
     * 走不到建档。
     *
     * <p>传 {@code null} 表示"以抽取结果为准"；传非空值则<b>覆盖</b>抽取结果。
     */
    public record IntakeConfirmRequest(@NotNull Integer expectedRevision, String externalRef,
                                       String name, String gender, String birthDate, String phone) {

        /** 只传版本号，不做任何补录。 */
        public IntakeConfirmRequest(Integer expectedRevision, String externalRef) {
            this(expectedRevision, externalRef, null, null, null, null);
        }
    }

    /** 确认结果：正式档案与写入的指标数。 */
    public record IntakeConfirmResult(String examId, String patientId, String patientName,
                                      int metricCount, String status) {
    }
}
