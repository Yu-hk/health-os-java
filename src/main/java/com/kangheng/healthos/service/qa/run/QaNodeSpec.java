package com.kangheng.healthos.service.qa.run;

import java.util.List;

/**
 * 问答运行的节点规格。
 *
 * <p>节点顺序即执行顺序，<b>但三类节点性质不同</b>：
 * <ul>
 *   <li>{@code retrieval}——准备阶段。检索一次，把命中片段冻结进 context 快照。
 *       三路分支共用同一份命中，所以它必须排在所有分支之前；</li>
 *   <li>各分支节点——每个 {@link com.kangheng.healthos.spi.qa.AnswerBranch}
 *       自动对应一个节点。新增一个分支实现，这里就多一个节点，
 *       续跑粒度、事件流、失败重试全都自动跟上；</li>
 *   <li>{@code summary}——汇总。读各分支节点的结果，合成最终答案并落库。</li>
 * </ul>
 *
 * <p>为什么不把分支节点写死成三个：原项目 {@code qa_prompts.NODE_KEYS} 把
 * {@code ('ragflow','modern','tcm')} 写死，加第四路要同时改 worker、节点表与 prompt。
 * 这里改成"分支实现即节点"，编排与接口都不用动。
 */
public final class QaNodeSpec {

    /** 检索节点：准备阶段，产出命中的原文片段。 */
    public static final String RETRIEVAL = "retrieval";

    /** 汇总节点：把各分支结果合成最终答案。 */
    public static final String SUMMARY = "summary";

    private QaNodeSpec() {
    }

    /**
     * 完整的节点顺序：检索 → 各分支（按 order 排序）→ 汇总。
     *
     * @param branchKeys 各分支的 key，调用方需保证已按 order 排好
     */
    public static List<String> ordered(List<String> branchKeys) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        keys.add(RETRIEVAL);
        keys.addAll(branchKeys);
        keys.add(SUMMARY);
        return List.copyOf(keys);
    }

    /** 分支节点是否在汇总之前。 */
    public static boolean isBranch(String nodeKey) {
        return !RETRIEVAL.equals(nodeKey) && !SUMMARY.equals(nodeKey);
    }
}
