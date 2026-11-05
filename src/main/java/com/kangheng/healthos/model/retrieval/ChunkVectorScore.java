package com.kangheng.healthos.model.retrieval;

import lombok.Data;

/**
 * 一条「切片 → 相似度」的查询结果。
 *
 * <p>只用于 pgvector 的 ANN 查询回填：{@code score} 是 cosine 相似度（{@code 1 - 余弦距离}），
 * 与 Java 侧 {@code EmbeddingModel.cosine} 同一口径，调用方不需要区分分数是从哪算出来的。
 */
@Data
public class ChunkVectorScore {

    private String chunkId;

    /** [0,1]。ANN 拿到的是原始 cosine，可能为负，由调用方截断。 */
    private Double score;
}
