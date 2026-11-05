package com.kangheng.healthos.provider.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.model.classics.DocumentChunkEntity;
import com.kangheng.healthos.provider.retrieval.ChunkVectorService;
import com.kangheng.healthos.spi.retrieval.KnowledgeBase;
import com.kangheng.healthos.spi.retrieval.RetrievalReranker;

/**
 * 本地检索的召回结果必须自带文档归属。
 *
 * <p>缘由：跨库检索（患者绑定的知识库）会把多份文档的片段混在一批结果里。
 * 一旦 {@code documentId} 在 provider 层丢掉，上层就算拿到 {@code chunkId} 也无从定位原文，
 * 引用只能显示一段没有出处的文字。这条用例把"provider 必须填文档归属"钉住。
 */
class LocalKnowledgeBaseTest {

    @Test
    @DisplayName("命中片段带出所属文档 ID")
    void hitsCarryDocumentId() {
        ChunkIndexService chunkIndexService = mock(ChunkIndexService.class);
        when(chunkIndexService.chunks(eq("doc_abc"), anyInt()))
                .thenReturn(List.of(chunk("chk_1", "doc_abc", "四君子汤主治脾胃气虚", 2, 180, 195)));

        LocalKnowledgeBase knowledgeBase = new LocalKnowledgeBase(chunkIndexService, null, new HealthOsProperties());
        List<KnowledgeBase.RetrievedChunk> hits = knowledgeBase.retrieve("doc_abc", "四君子汤", 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).documentId()).isEqualTo("doc_abc");
        assertThat(hits.get(0).chunkId()).isEqualTo("chk_1");
        // 页码与偏移来自本地切片，不是猜的
        assertThat(hits.get(0).page()).isEqualTo(2);
        assertThat(hits.get(0).charStart()).isEqualTo(180);
    }

    @Test
    @DisplayName("文档归属用本次检索的 documentId，不取自切片行的冗余字段")
    void documentIdComesFromRetrieveArgument() {
        ChunkIndexService chunkIndexService = mock(ChunkIndexService.class);
        when(chunkIndexService.chunks(eq("doc_abc"), anyInt()))
                .thenReturn(List.of(chunk("chk_1", "doc_abc", "黄芪补气升阳", 1, 0, 6)));

        LocalKnowledgeBase knowledgeBase = new LocalKnowledgeBase(chunkIndexService, null, new HealthOsProperties());
        List<KnowledgeBase.RetrievedChunk> hits = knowledgeBase.retrieve("doc_abc", "黄芪", 5);

        assertThat(hits).allSatisfy(hit -> assertThat(hit.documentId()).isEqualTo("doc_abc"));
    }

    private DocumentChunkEntity chunk(String id, String documentId, String content,
                                      int page, int charStart, int charEnd) {
        DocumentChunkEntity entity = new DocumentChunkEntity();
        entity.setId(id);
        entity.setDocumentId(documentId);
        entity.setContent(content);
        entity.setChunkIndex(1);
        entity.setPageFrom(page);
        entity.setCharStart(charStart);
        entity.setCharEnd(charEnd);
        return entity;
    }

    @Test
    @DisplayName("向量命中落在词面窗口外时，仍作为候选被召回")
    void denseOnlyHitBecomesCandidate() {
        ChunkIndexService chunkIndexService = mock(ChunkIndexService.class);
        ChunkVectorService vectorService = mock(ChunkVectorService.class);

        // 词面窗口里只有 c1；向量那头召回了 c2（在长文档靠后的位置）
        when(chunkIndexService.chunks(eq("doc_long"), anyInt()))
                .thenReturn(List.of(chunk("c1", "doc_long", "四君子汤主治脾胃气虚", 2, 180, 195)));
        when(vectorService.search(eq("doc_long"), eq("补肾药材"), anyInt()))
                .thenReturn(Map.of("c2", 0.9));
        when(chunkIndexService.byIds(anyCollection()))
                .thenReturn(List.of(chunk("c2", "doc_long", "熟地黄补益肝肾", 50, 9000, 9012)));

        LocalKnowledgeBase knowledgeBase =
                new LocalKnowledgeBase(chunkIndexService, vectorService, new HealthOsProperties());
        List<KnowledgeBase.RetrievedChunk> hits = knowledgeBase.retrieve("doc_long", "补肾药材", 5);

        // c2 不在词面窗口，但凭向量进入了候选集——否则长文档后半本永远召不回
        assertThat(hits).extracting(KnowledgeBase.RetrievedChunk::chunkId).contains("c2");
    }

    @Test
    @DisplayName("开启重排时，最终顺序按 cross-encoder 分数排，覆盖融合序")
    void rerankOverridesFusedOrder() {
        ChunkIndexService chunkIndexService = mock(ChunkIndexService.class);
        ChunkVectorService vectorService = mock(ChunkVectorService.class);
        when(chunkIndexService.chunks(eq("doc_x"), anyInt())).thenReturn(List.of(
                chunk("c1", "doc_x", "四君子汤主治脾胃气虚", 2, 180, 195),
                chunk("c2", "doc_x", "黄芪补气固表", 3, 200, 210),
                chunk("c3", "doc_x", "熟地黄补血滋阴", 4, 220, 230)));
        // 融合序会是 c3(0.7) > c2(0.6) > c1(0.5)
        when(vectorService.search(eq("doc_x"), eq("补气"), anyInt()))
                .thenReturn(Map.of("c1", 0.5, "c2", 0.6, "c3", 0.7));

        // 重排器给出与融合序不同的顺序：c1 最相关、c2 次之、c3 最不相关
        RetrievalReranker reranker = new FakeReranker(true,
                List.of(new RetrievalReranker.RankedPassage("c1", 0.9),
                        new RetrievalReranker.RankedPassage("c2", 0.5),
                        new RetrievalReranker.RankedPassage("c3", 0.1)));
        LocalKnowledgeBase knowledgeBase = new LocalKnowledgeBase(
                chunkIndexService, vectorService, new HealthOsProperties(), reranker);
        List<KnowledgeBase.RetrievedChunk> hits = knowledgeBase.retrieve("doc_x", "补气", 3);

        assertThat(hits).extracting(KnowledgeBase.RetrievedChunk::chunkId)
                .containsExactly("c1", "c2", "c3");
    }

    @Test
    @DisplayName("重排器抛异常时退回融合序，不冒泡到问答")
    void rerankFailureFallsBackToFusedOrder() {
        ChunkIndexService chunkIndexService = mock(ChunkIndexService.class);
        ChunkVectorService vectorService = mock(ChunkVectorService.class);
        when(chunkIndexService.chunks(eq("doc_y"), anyInt())).thenReturn(List.of(
                chunk("c1", "doc_y", "四君子汤", 2, 180, 195),
                chunk("c2", "doc_y", "黄芪", 3, 200, 210),
                chunk("c3", "doc_y", "熟地黄", 4, 220, 230)));
        when(vectorService.search(eq("doc_y"), eq("补气"), anyInt()))
                .thenReturn(Map.of("c1", 0.5, "c2", 0.6, "c3", 0.7));

        // 可用，但 rerank() 抛异常——必须退回融合序、不报错
        RetrievalReranker reranker = new FakeReranker(true, null) {
            @Override
            public List<RankedPassage> rerank(String query, List<Passage> passages) {
                throw new IllegalStateException("rerank 端点 503");
            }
        };
        LocalKnowledgeBase knowledgeBase = new LocalKnowledgeBase(
                chunkIndexService, vectorService, new HealthOsProperties(), reranker);
        List<KnowledgeBase.RetrievedChunk> hits = knowledgeBase.retrieve("doc_y", "补气", 3);

        // 融合序 c3 > c2 > c1，降级后应保持
        assertThat(hits).extracting(KnowledgeBase.RetrievedChunk::chunkId)
                .containsExactly("c3", "c2", "c1");
    }

    /** 测试用假重排器：按构造时给定的顺序返回，或 available() 返回指定值。 */
    static class FakeReranker implements RetrievalReranker {
        private final boolean available;
        private final List<RetrievalReranker.RankedPassage> ranked;

        FakeReranker(boolean available, List<RetrievalReranker.RankedPassage> ranked) {
            this.available = available;
            this.ranked = ranked;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public List<RetrievalReranker.RankedPassage> rerank(String query,
                                                          List<RetrievalReranker.Passage> passages) {
            return ranked;
        }
    }
}
