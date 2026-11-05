package com.kangheng.healthos.provider.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.mapper.classics.DocumentChunkMapper;
import com.kangheng.healthos.mapper.retrieval.ChunkVectorMapper;
import com.kangheng.healthos.model.retrieval.ChunkVectorEntity;
import com.kangheng.healthos.model.retrieval.ChunkVectorScore;
import com.kangheng.healthos.spi.ai.EmbeddingModel;

import org.springframework.dao.DataAccessException;

/**
 * ANN 路径与内存路径的切换语义。
 *
 * <p>两条路都返回 cosine、都截断到 [0,1]，融合排序不关心分数来源。这里把"切到哪条路、
 * 切失败会不会退回去"钉住——这是 pgvector 改造最容易悄悄出错的地方：
 * 索引建了但查询仍走全量，或者索引报错把整次检索打挂。
 */
@DisplayName("ChunkVectorService：ANN 与内存路径")
class ChunkVectorServiceAnnTest {

    private ChunkVectorService service(boolean ann, EmbeddingModel embedding, ChunkVectorMapper mapper) {
        PgVectorIndex index = mock(PgVectorIndex.class);
        when(index.available()).thenReturn(ann);
        return new ChunkVectorService(mapper, mock(DocumentChunkMapper.class),
                embedding, index, new HealthOsProperties());
    }

    private EmbeddingModel embedding() {
        EmbeddingModel embedding = mock(EmbeddingModel.class);
        when(embedding.enabled()).thenReturn(true);
        when(embedding.id()).thenReturn("ollama:bge-m3");
        when(embedding.dimension()).thenReturn(2);
        when(embedding.embed(anyString())).thenReturn(new float[] { 1f, 0f });
        return embedding;
    }

    private ChunkVectorEntity row(String chunkId, float[] vec) {
        ChunkVectorEntity row = new ChunkVectorEntity();
        row.setChunkId(chunkId);
        row.setVector(Json.write(vec));
        return row;
    }

    @Test
    @DisplayName("ANN 未启用时走内存余弦，不碰 searchTopK")
    void memoryPathWhenAnnOff() {
        ChunkVectorMapper mapper = mock(ChunkVectorMapper.class);
        when(mapper.listByDocument(eq("doc1"), eq("ollama:bge-m3")))
                .thenReturn(List.of(row("c1", new float[] { 1f, 0f }), row("c2", new float[] { 0f, 1f })));
        ChunkVectorService service = service(false, embedding(), mapper);

        Map<String, Double> scores = service.search("doc1", "q", 5);

        assertThat(scores).containsEntry("c1", 1.0);
        assertThat(scores).containsEntry("c2", 0.0);
        verify(mapper, never()).searchTopK(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("ANN 启用时只取 top-K，分数原样返回（已截断到 [0,1]）")
    void annPathTopK() {
        ChunkVectorMapper mapper = mock(ChunkVectorMapper.class);
        when(mapper.searchTopK(eq("doc1"), eq("ollama:bge-m3"), anyString(), eq(5)))
                .thenReturn(List.of(score("c1", 0.93), score("c2", -0.2)));
        ChunkVectorService service = service(true, embedding(), mapper);

        Map<String, Double> scores = service.search("doc1", "q", 5);

        // 负分被截断到 0，和内存路径一致
        assertThat(scores).containsEntry("c1", 0.93);
        assertThat(scores).containsEntry("c2", 0.0);
        verify(mapper, never()).listByDocument(anyString(), anyString());
    }

    @Test
    @DisplayName("ANN 查询报错时退回内存路径，而不是返回空或抛异常")
    void annQueryFailureFallsBackToMemory() {
        ChunkVectorMapper mapper = mock(ChunkVectorMapper.class);
        // 第一次（ANN）失败，第二次（内存）有数据
        when(mapper.searchTopK(eq("doc1"), anyString(), anyString(), eq(5)))
                .thenThrow(mock(DataAccessException.class));
        when(mapper.listByDocument(eq("doc1"), eq("ollama:bge-m3")))
                .thenReturn(List.of(row("c1", new float[] { 1f, 0f })));
        ChunkVectorService service = service(true, embedding(), mapper);

        Map<String, Double> scores = service.search("doc1", "q", 5);

        assertThat(scores).containsEntry("c1", 1.0);
        verify(mapper, times(1)).searchTopK(anyString(), anyString(), anyString(), anyInt());
        verify(mapper, times(1)).listByDocument(anyString(), anyString());
    }

    private static ChunkVectorScore score(String chunkId, double value) {
        ChunkVectorScore s = new ChunkVectorScore();
        s.setChunkId(chunkId);
        s.setScore(value);
        return s;
    }
}
