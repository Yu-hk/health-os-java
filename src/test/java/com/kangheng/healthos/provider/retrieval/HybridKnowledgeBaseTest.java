package com.kangheng.healthos.provider.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.kangheng.healthos.model.classics.DocumentChunkEntity;
import com.kangheng.healthos.spi.retrieval.KnowledgeBase;

/**
 * 跨库检索的定位必须"每条认自己的文档"。
 *
 * <p>修过的真实缺陷：原实现把所有命中都拿<b>同一个</b> documentId 去本地切片表定位，
 * 跨库（患者绑定了多个库）时只有碰巧属于那份文档的片段能补上页码，其余全部丢定位——
 * 表现是引用有文字、没有页码，且文档归属全是同一个。
 */
class HybridKnowledgeBaseTest {

    @Test
    @DisplayName("跨库命中各自按自己的 documentId 补回页码，互不串用")
    void datasetsEnrichPerHitDocument() {
        LocalKnowledgeBase local = mock(LocalKnowledgeBase.class);
        RagflowKnowledgeBase ragflow = mock(RagflowKnowledgeBase.class);
        ChunkIndexService chunkIndexService = mock(ChunkIndexService.class);

        when(ragflow.retrieveDatasets(anyList(), anyString(), anyInt())).thenReturn(List.of(
                new KnowledgeBase.RetrievedChunk("rf_1", "doc_book", "四君子汤益气健脾", null, null, null, 0.9),
                new KnowledgeBase.RetrievedChunk("rf_2", "doc_note", "患者既往有高血压病史", null, null, null, 0.7)));

        when(chunkIndexService.locate(eq("doc_book"), eq("四君子汤益气健脾")))
                .thenReturn(Optional.of(chunk("chk_a", "doc_book", 3, 100, 108)));
        when(chunkIndexService.locate(eq("doc_note"), eq("患者既往有高血压病史")))
                .thenReturn(Optional.of(chunk("chk_b", "doc_note", 1, 20, 31)));

        HybridKnowledgeBase hybrid = new HybridKnowledgeBase(local, ragflow, chunkIndexService);
        List<KnowledgeBase.RetrievedChunk> hits = hybrid.retrieveDatasets(List.of("ds_1", "ds_2"), "高血压用药", 5);

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).documentId()).isEqualTo("doc_book");
        assertThat(hits.get(0).page()).isEqualTo(3);
        assertThat(hits.get(0).charStart()).isEqualTo(100);
        assertThat(hits.get(1).documentId()).isEqualTo("doc_note");
        assertThat(hits.get(1).page()).isEqualTo(1);
        assertThat(hits.get(1).charStart()).isEqualTo(20);
    }

    @Test
    @DisplayName("认不出文档归属就留空页码，不拿别的文档顶替")
    void unknownDocumentLeavesOffsetsNull() {
        LocalKnowledgeBase local = mock(LocalKnowledgeBase.class);
        RagflowKnowledgeBase ragflow = mock(RagflowKnowledgeBase.class);
        ChunkIndexService chunkIndexService = mock(ChunkIndexService.class);

        when(ragflow.retrieveDatasets(anyList(), anyString(), anyInt())).thenReturn(List.of(
                // 引擎没给 document_id（或映射不到本系统文档）
                new KnowledgeBase.RetrievedChunk("rf_1", null, "某段无出处的文字", null, null, null, 0.5)));

        HybridKnowledgeBase hybrid = new HybridKnowledgeBase(local, ragflow, chunkIndexService);
        List<KnowledgeBase.RetrievedChunk> hits = hybrid.retrieveDatasets(List.of("ds_1"), "问题", 5);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).documentId()).isNull();
        assertThat(hits.get(0).page()).isNull();
        assertThat(hits.get(0).charStart()).isNull();
    }

    private DocumentChunkEntity chunk(String id, String documentId, int page, int start, int end) {
        DocumentChunkEntity entity = new DocumentChunkEntity();
        entity.setId(id);
        entity.setDocumentId(documentId);
        entity.setPageFrom(page);
        entity.setCharStart(start);
        entity.setCharEnd(end);
        return entity;
    }
}
