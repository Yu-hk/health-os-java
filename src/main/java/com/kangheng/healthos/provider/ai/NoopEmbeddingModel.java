package com.kangheng.healthos.provider.ai;

import java.util.List;

import com.kangheng.healthos.spi.ai.EmbeddingException;
import com.kangheng.healthos.spi.ai.EmbeddingModel;

/**
 * 未启用向量化时的占位实现。
 *
 * <p>存在的意义是让"向量检索关掉了"变成**一个合法状态**，而不是一个 null。
 * 调用方不用到处判空，也不会因为某处漏判而 NPE；{@link #enabled()} 为 false 时
 * 检索直接走词面打分，行为与加向量之前完全一致。
 */
public class NoopEmbeddingModel implements EmbeddingModel {

    public static final String ID = "none";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int dimension() {
        return 0;
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public float[] embed(String text) {
        throw new EmbeddingException("EMBEDDING_DISABLED", "向量化未启用（healthos.embedding.provider=none）", false);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        throw new EmbeddingException("EMBEDDING_DISABLED", "向量化未启用（healthos.embedding.provider=none）", false);
    }

    @Override
    public boolean healthy() {
        return true;
    }
}
