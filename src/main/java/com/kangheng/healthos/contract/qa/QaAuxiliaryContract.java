package com.kangheng.healthos.contract.qa;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;

/**
 * QA 域辅助契约：书签/备忘（对齐原工程 {@code qa_memos.py}）与单路 RAG 诊断
 * （对齐原工程 {@code qa_diagnostics.py}）共用的请求/响应结构。
 *
 * <p>两个功能都长在 QA 域、都按 owner 隔离、都要"显式触发"，因此合用一个契约类：
 * 分开建文件只会让两个几乎同构的分页/详情结构各写一遍，读的人反而更难对上。
 *
 * <p><b>为什么用 record 而不是 DTO 类</b>：这些结构都是"一次组装完就不再改"的值，
 * record 的不可变正好保证服务层组装好之后不会被下游顺手改掉；JSON 字段名由组件名决定，
 * 不需要额外的 {@code @JsonProperty}。
 */
public final class QaAuxiliaryContract {

    private QaAuxiliaryContract() {
    }

    // ================================================================ 功能 A：书签 / 备忘

    /**
     * 创建备忘的响应。
     *
     * <p>只回定位信息，不回正文：正文可能很长，前端拿到 {@code memoId} 后走详情接口取，
     * 避免列表/创建这种高频路径把整篇答案来回搬运。
     */
    public record MemoCreated(String memoId, String patientId, String sourceMessageId,
                              OffsetDateTime createdAt) {
    }

    /** 列表项：摘要视图，不含 content / evidenceSnapshot 这类大字段。 */
    public record MemoItem(String memoId, String sourceMessageId, String question,
                           String contentHash, OffsetDateTime createdAt) {
    }

    /**
     * 备忘列表（键集分页）。
     *
     * @param nextCursor 下一页游标；为 {@code null} 表示已到末尾。
     *                   游标是 {@code created_at + id} 的编码，不用 offset——
     *                   offset 在"有人在翻页时又存了一条新备忘"时会漏/重
     */
    public record MemoPage(List<MemoItem> items, String nextCursor) {
    }

    /**
     * 备忘完整视图。
     *
     * <p>{@code evidenceSnapshot} / {@code sourceVersions} 从库里的 TEXT JSON 列还原成
     * 真正的 JSON 数组（而不是把 JSON 字符串塞给前端再解析一次）——
     * 详情接口本来就是"取出来看"的，没必要让客户端再做一次反序列化。
     */
    public record MemoDetail(String memoId, String patientId, String sourceMessageId,
                             OffsetDateTime createdAt, String question, String content,
                             List<Map<String, Object>> evidenceSnapshot,
                             List<Map<String, Object>> sourceVersions, String contentHash) {
    }

    // ================================================================ 功能 B：单路 RAG 诊断

    /**
     * 提交诊断的请求。
     *
     * @param expectedVersion 调用方看到的绑定版本，仅作留痕与幂等输入之一；
     *                        服务端不据此做乐观锁，绑定本身没有版本列
     */
    public record DiagnosticRequest(@NotBlank String question, Integer expectedVersion) {
    }

    /** 受理结果（202）。{@code statusUrl} 同时作为 {@code Location} 头返回。 */
    public record DiagnosticAccepted(String runId, String bindingId, String statusUrl) {
    }

    /** 会话维度列表项。 */
    public record DiagnosticItem(String runId, String bindingId, String question,
                                 String status, OffsetDateTime createdAt) {
    }

    /** 诊断列表（键集分页，按 created_at / id 倒序）。 */
    public record DiagnosticPage(List<DiagnosticItem> items, String nextCursor) {
    }

    /**
     * 一条证据。
     *
     * <p>{@code snippet} 是<b>截断后的</b>片段，不是整篇正文：诊断结果会被反复拉取，
     * 把整篇塞进去既撑大响应，也让"诊断"变成"顺手把原文导走"的旁路。
     */
    public record DiagnosticEvidence(String evidenceId, String sourceKind, String title,
                                     String contentHash, String snippet) {
    }

    /** 详情里的错误；成功时为 {@code null}。 */
    public record DiagnosticError(String code, String message) {
    }

    /**
     * 诊断详情。
     *
     * @param mode   固定 {@code ragflow_diagnostic}，前端据此区别于普通问答运行
     * @param result 执行结果 Map（executionStatus / retrievalStatus / resultKind / ...）；
     *               {@code evidence} 明细单独放在 {@code evidence} 字段，不在这份 Map 里重复
     * @param draft  检索片段摘要。失败时也保留——对齐原工程"已完成结果保留"语义
     */
    public record DiagnosticDetail(String runId, String bindingId, String mode, String question,
                                   String status, String draft, Map<String, Object> result,
                                   DiagnosticError error, OffsetDateTime createdAt,
                                   OffsetDateTime startedAt, OffsetDateTime completedAt,
                                   List<DiagnosticEvidence> evidence) {
    }
}
