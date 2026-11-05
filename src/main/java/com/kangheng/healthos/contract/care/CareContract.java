package com.kangheng.healthos.contract.care;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 健康管理域契约。
 *
 * <p>分节结构对齐原工程 {@code care_contract.py}：
 * <ul>
 *   <li>报告（report）6 节：{@code profile / modern_assessment / tcm_assessment / diet / exercise / rest}；</li>
 *   <li>计划（plan）5 节：{@code framework / week_1..week_4}。</li>
 * </ul>
 *
 * <p>每节都带 {@code metricRefs}——记录这一节的结论引用了哪些体检指标。
 * 这是"结论有据可查"的载体：没有依据的句子不该出现在签发报告里。
 */
public final class CareContract {

    private CareContract() {
    }

    // ------------------------------------------------------------ 分节定义

    /** 报告分节。顺序即展示顺序。 */
    public static final List<String> REPORT_KEYS = List.of(
            "profile", "modern_assessment", "tcm_assessment", "diet", "exercise", "rest");

    /** 计划分节。 */
    public static final List<String> PLAN_KEYS = List.of(
            "framework", "week_1", "week_2", "week_3", "week_4");

    public static final Map<String, String> SECTION_TITLES = Map.ofEntries(
            Map.entry("profile", "综合健康画像"),
            Map.entry("modern_assessment", "现代医学评估"),
            Map.entry("tcm_assessment", "中医分析"),
            Map.entry("diet", "饮食干预建议"),
            Map.entry("exercise", "运动干预建议"),
            Map.entry("rest", "作息与生活方式建议"),
            Map.entry("framework", "四周计划总体框架"),
            Map.entry("week_1", "第 1 周执行计划"),
            Map.entry("week_2", "第 2 周执行计划"),
            Map.entry("week_3", "第 3 周执行计划"),
            Map.entry("week_4", "第 4 周执行计划"));

    /**
     * 对外免责声明。
     *
     * <p>三件事必须写在里面：内容用于健康管理而非独立诊断；模型知识未经检索核实；
     * "签发"是登录医生身份确认，不是硬件 CA 电子签名。
     */
    public static final String DISCLAIMER =
            "本内容用于健康管理与专业辅助，基于已确认体检资料及模型内置知识生成，"
                    + "不构成独立诊断或处方。模型知识并非已检索或核实的指南证据。"
                    + "实际干预需由具备权限的专业人员审核；出现不适应停止相关活动并及时就医。"
                    + "本系统的签发为登录医生身份确认记录，不代表硬件 CA 电子签名。";

    // ------------------------------------------------------------ 请求

    /**
     * 生成报告。
     *
     * @param examId        来源体检录入单 ID（必须是已确认状态）
     * @param idempotencyKey 幂等键；同键同输入返回同一份，不重复生成
     */
    public record CreateReportRequest(@NotBlank String examId, String idempotencyKey) {
    }

    /** 编辑单个分节。 */
    public record EditSectionRequest(@NotNull Integer expectedRevision,
                                     @NotBlank @Size(min = 10, max = 8000) String text,
                                     List<String> metricRefs) {
    }

    /**
     * 签发。
     *
     * @param expectedRevision 乐观锁：客户端声称的当前修订号
     * @param reviewComment    审核意见，有效字符少于 5 个会被拒
     * @param startDate        计划起始日期（签发报告时同步生成计划）
     */
    public record SignRequest(@NotNull Integer expectedRevision,
                              @NotBlank @Size(max = 2000) String reviewComment,
                              String startDate) {
    }

    /** 确认计划生效。 */
    public record ConfirmPlanRequest(@NotNull Integer expectedRevision) {
    }

    /** 打卡。 */
    public record CheckinRequest(Boolean completed, @NotNull Integer expectedVersion) {
    }

    /** 修订：基于已签发报告生成新草稿。 */
    public record ReviseRequest(@NotNull Integer expectedRevision) {
    }

    // ------------------------------------------------------------ 响应

    /** 一节内容。 */
    public record SectionView(String key, String title, String text, List<String> metricRefs,
                              boolean present) {
    }

    /** 计划里的一个任务。 */
    public record TaskView(String taskId, String category, String title, String content,
                           String target, int dayIndex, boolean completed, Integer version) {
    }

    /** 打卡汇总。 */
    public record CheckinSummary(int total, int completed, int pending) {
    }

    /** 文档视图。 */
    public record DocumentView(String id, String owner, String kind, String patientId,
                               String status, Integer revision, Integer signedRevision,
                               Integer activeRevision, String sourceExamId,
                               Integer sourceExamRevision, String sourceReportId,
                               Integer sourceReportRevision, String parentDocumentId,
                               String startDate, boolean complete, List<SectionView> sections,
                               List<TaskView> tasks, CheckinSummary checkin,
                               SignatureView signature, String disclaimer,
                               OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /** 签发视图。 */
    public record SignatureView(String signer, String reviewComment, Integer signedRevision,
                                String contentHash, String startDate, String kind,
                                OffsetDateTime signedAt) {
    }

    /** 修订历史。 */
    public record RevisionView(String id, Integer revision, String actor, String reason,
                               OffsetDateTime createdAt) {
    }

    /** 审计条目。 */
    public record AuditView(String id, Integer revision, String actor, String action,
                            String reason, String changedSection, OffsetDateTime createdAt) {
    }

    /** 创建报告的受理结果。 */
    public record AcceptedResponse(String documentId, String status, boolean reused) {
    }
}
