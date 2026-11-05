package com.kangheng.healthos.spi.qa;

import java.util.List;

import com.kangheng.healthos.model.qa.Citation;

/**
 * 单路分支的执行结果。
 *
 * <p>状态划分对齐原工程 {@code qa_prompts.NODE_KEYS} 的节点语义：
 * <ul>
 *   <li>{@link #SUCCEEDED}——拿到可用答案；</li>
 *   <li>{@link #INSUFFICIENT}——分支本身正常，但该视角确实没有依据（例如原文检索为空）。
 *       这是<b>正常结果</b>，不是故障，不能算进 {@code QA_BRANCHES_INCOMPLETE}；</li>
 *   <li>{@link #SKIPPED}——前置条件不满足（例如没传 patientId，现代医学视角无据可依）；</li>
 *   <li>{@link #FAILED}——执行出错或超时，属于"该路没做完"。</li>
 * </ul>
 *
 * <p>{@code basis} 沿用原工程 {@code BASES} 取值：
 * {@code patient_record} / {@code retrieved_evidence} / {@code model_knowledge} / {@code mixed}，
 * 用于让前端与审计侧一眼看出这条结论的凭据类型。
 */
public record BranchResult(String key, String label, String status, String basis,
                           String answer, List<Citation> citations,
                           String errorCode, long elapsedMs, String note) {

    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String INSUFFICIENT = "INSUFFICIENT_EVIDENCE";
    public static final String SKIPPED = "SKIPPED";
    public static final String FAILED = "FAILED";

    public static final String BASIS_PATIENT_RECORD = "patient_record";
    public static final String BASIS_RETRIEVED_EVIDENCE = "retrieved_evidence";
    public static final String BASIS_MODEL_KNOWLEDGE = "model_knowledge";
    public static final String BASIS_MIXED = "mixed";

    public BranchResult {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    public boolean succeeded() {
        return SUCCEEDED.equals(status);
    }

    public boolean failed() {
        return FAILED.equals(status);
    }

    public static BranchResult succeeded(String key, String label, String basis, String answer,
                                         List<Citation> citations, long elapsedMs) {
        return new BranchResult(key, label, SUCCEEDED, basis, answer, citations, null, elapsedMs, null);
    }

    public static BranchResult insufficient(String key, String label, String note, long elapsedMs) {
        return new BranchResult(key, label, INSUFFICIENT, null, null, List.of(), null, elapsedMs, note);
    }

    public static BranchResult skipped(String key, String label, String note, long elapsedMs) {
        return new BranchResult(key, label, SKIPPED, BASIS_MODEL_KNOWLEDGE, null, List.of(),
                null, elapsedMs, note);
    }

    public static BranchResult failed(String key, String label, String errorCode, String note,
                                      long elapsedMs) {
        return new BranchResult(key, label, FAILED, null, null, List.of(), errorCode, elapsedMs, note);
    }
}
