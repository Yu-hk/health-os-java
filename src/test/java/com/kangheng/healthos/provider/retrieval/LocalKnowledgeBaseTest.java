package com.kangheng.healthos.provider.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.model.classics.DocumentChunkEntity;
import com.kangheng.healthos.spi.retrieval.KnowledgeBase;

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
}
