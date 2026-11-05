package com.kangheng.healthos.provider.retrieval;

import java.util.List;

import com.kangheng.healthos.spi.retrieval.RetrievalReranker;

/**
 * 不启用重排时的占位实现：永远 {@link #available()} 返回 {@code false}，
 * 上层因此不会调用 {@link #rerank}，检索走融合序。
 *
 * <p>做成显式占位而不是 {@code null}，是为了让装配点统一、避免到处判空。
 */
public final class NoopReranker implements RetrievalReranker {

    public static final NoopReranker INSTANCE = new NoopReranker();

    private NoopReranker() {
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public List<RankedPassage> rerank(String query, List<Passage> passages) {
        throw new UnsupportedOperationException("重排未启用");
    }

    @Override
    public String name() {
        return "noop";
    }
}
