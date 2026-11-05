package com.kangheng.healthos.service.qa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kangheng.healthos.model.qa.Citation;
import com.kangheng.healthos.spi.qa.BranchResult;

/**
 * 分支结果汇总。
 *
 * <p>抽出来的原因：原实现藏在 {@code BranchOrchestrator} 的私有方法里，
 * 只有"一次性并行跑完"这一条路能用它。而持久化 run 需要另一条路——
 * 各分支分节点跑、各自落库，之后再汇总。
 * 两条路必须给出<b>同一个答案</b>，否则"同步问一次"和"跑一个 run"结果不一致，
 * 这本身就是 bug。所以汇总规则只写一份，两边共用。
 */
public final class BranchAggregator {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_PARTIAL = "BRANCHES_INCOMPLETE";
    public static final String STATUS_INSUFFICIENT = "INSUFFICIENT_EVIDENCE";

    public static final String ERROR_INCOMPLETE = "QA_BRANCHES_INCOMPLETE";

    private BranchAggregator() {
    }

    /**
     * @param status     OK / BRANCHES_INCOMPLETE / INSUFFICIENT_EVIDENCE
     * @param errorCode  仅在不完整或全无依据时给出
     * @param branches   每路明细，按传入顺序
     */
    public record Aggregate(String status, String errorCode, String answer,
                            List<Citation> citations, List<BranchResult> branches) {
    }

    /** 汇总：拼装已成功的路，把没纳入的如实写清。 */
    public static Aggregate combine(List<BranchResult> results) {
        List<BranchResult> succeeded = results.stream().filter(BranchResult::succeeded).toList();
        List<BranchResult> failed = results.stream().filter(BranchResult::failed).toList();

        List<Citation> citations = new ArrayList<>();
        Map<String, Citation> deduped = new LinkedHashMap<>();
        for (BranchResult result : succeeded) {
            for (Citation citation : result.citations()) {
                String key = citation.evidenceId() + "|" + citation.page() + "|" + citation.charStart();
                deduped.putIfAbsent(key, citation);
            }
        }
        citations.addAll(deduped.values());

        if (succeeded.isEmpty()) {
            String errorCode = failed.isEmpty() ? null : failed.get(0).errorCode();
            return new Aggregate(STATUS_INSUFFICIENT, errorCode, null, List.of(), results);
        }

        StringBuilder answer = new StringBuilder();
        if (succeeded.size() == 1) {
            answer.append(succeeded.get(0).answer().strip());
        } else {
            for (BranchResult result : succeeded) {
                answer.append("【").append(result.label()).append("】")
                        .append(result.answer().strip()).append("\n\n");
            }
            answer = new StringBuilder(answer.toString().strip());
        }
        List<BranchResult> excluded = results.stream().filter(item -> !item.succeeded()).toList();
        if (!excluded.isEmpty()) {
            answer.append("\n\n未纳入汇总：");
            List<String> parts = new ArrayList<>();
            for (BranchResult result : excluded) {
                parts.add(result.label() + "（" + reasonOf(result) + "）");
            }
            answer.append(String.join("；", parts)).append('。');
        }

        String status = failed.isEmpty() ? STATUS_OK : STATUS_PARTIAL;
        String errorCode = failed.isEmpty() ? null : ERROR_INCOMPLETE;
        return new Aggregate(status, errorCode, answer.toString(), List.copyOf(citations), results);
    }

    private static String reasonOf(BranchResult result) {
        if (result.failed()) {
            return "执行失败：" + (result.note() == null ? "未知原因" : result.note());
        }
        if (BranchResult.SKIPPED.equals(result.status())) {
            return "未启用";
        }
        return "无依据";
    }
}
