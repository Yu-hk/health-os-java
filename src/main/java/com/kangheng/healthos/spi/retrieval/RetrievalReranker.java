package com.kangheng.healthos.spi.retrieval;

import java.util.List;

/**
 * 交叉编码器（cross-encoder）重排 SPI。
 *
 * <p>本地检索先用「词面 + 向量」加权和融合出一个候选集（见 {@code HybridScorer}），
 * 这一步是<b>双塔</b>思路：向量相似度与词面覆盖各算各的，分数可解释但语义交叉不足——
 * 比如"问题问的是 A 的副作用，某段讲 A 但重点在 B 的禁忌"，加权和可能把它排到很后面。
 * cross-encoder 把 (query, passage) 拼在一起过一次模型，直接打出"这一段回答这个问题的程度"，
 * 排序质量通常明显好于一阶融合。
 *
 * <p><b>它是增强，不是依赖</b>：实现方自己负责"端点/模型在不在"。{@link #available()} 返回
 * {@code false} 或 {@link #rerank} 抛异常时，上层退回融合序，问答照常出结果——和向量化没启用时
 * 退回纯词面一个道理。绝不能因为重排挂了就让检索报错。
 *
 * <p>契约：{@link #rerank} 返回的列表按相关性降序，长度不超过入参；它<b>只重排、可截断</b>，
 * 不新增任何入参里没有的 chunkId（重排看不到候选之外的文本，硬塞会编顺序）。
 */
public interface RetrievalReranker {

    /** 是否就绪（端点可达、模型已加载）。{@code false} 时上层直接用融合序。 */
    boolean available();

    /**
     * 对候选做交叉编码器重排。
     *
     * @param query     用户问题（已归一化，非空）
     * @param passages  候选片段，顺序即调用方融合序；实现方按索引回写分数，不要凭内容重新召回
     * @return 按相关性降序的 (chunkId, score)；score 语义由实现方定义（通常越大越相关）
     * @throws Exception 任意异常都视为"本次重排不可用"，上层退回融合序
     */
    List<RankedPassage> rerank(String query, List<Passage> passages) throws Exception;

    default String name() {
        return "reranker";
    }

    /** 重排入参：一段候选文本 + 它的身份。 */
    record Passage(String chunkId, String text) {
    }

    /** 重排出参：某候选的相关性分数。 */
    record RankedPassage(String chunkId, double score) {
    }
}
