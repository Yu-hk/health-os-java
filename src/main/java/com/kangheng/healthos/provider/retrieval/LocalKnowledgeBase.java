package com.kangheng.healthos.provider.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.kangheng.healthos.model.classics.DocumentChunkEntity;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.spi.retrieval.KnowledgeBase;

/**
 * 本地知识库：切片落 {@code document_chunk} 表，检索按「词面 + 向量」融合打分。
 *
 * <p>这是默认实现，也是 RAGFlow 不可用时的兜底。它**不依赖外部服务也能工作**：
 * 没有接 embedding 时退化为纯词面打分（结果可复现、可单测，排障时能确定问题出在
 * "召回"还是"生成"）；接了 embedding 之后，同义改写才召得回来。
 *
 * <p>融合分两个分量都保留在 {@link HybridScorer.Hit} 里：出问题时能一眼看出
 * "这条是被向量顶上来的，词面一个字都没对上"——这比一个混合后的黑盒分数有用。
 *
 * <p>不标 {@code @Component}：由 {@link com.kangheng.healthos.config.RetrievalConfig}
 * 按 {@code healthos.retrieval.provider} 显式装配，避免容器里出现多个 {@code KnowledgeBase} 候选。
 */
public class LocalKnowledgeBase implements KnowledgeBase {

    public static final String ENGINE = "local-chunk-index";

    private final ChunkIndexService chunkIndexService;
    private final ChunkVectorService vectorService;
    private final HealthOsProperties properties;

    public LocalKnowledgeBase(ChunkIndexService chunkIndexService,
                              ChunkVectorService vectorService,
                              HealthOsProperties properties) {
        this.chunkIndexService = chunkIndexService;
        this.vectorService = vectorService;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public IndexOutcome index(String documentId, String title, String markdown) {
        long started = System.nanoTime();
        int chunks = chunkIndexService.index(documentId, markdown);
        // 向量化在切片落库之后单独跑：它可能要几百毫秒到几秒，不该待在切片事务里
        String note = vectorize(documentId);
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        return new IndexOutcome(ENGINE, chunks, documentId, elapsed, note);
    }

    private String vectorize(String documentId) {
        if (vectorService == null || !vectorService.enabled()) {
            return null;
        }
        ChunkVectorService.Stats stats = vectorService.indexDocument(documentId);
        if (stats.note() != null) {
            // 降级必须留痕：否则"检索变差了"会被当成模型不行，而不是向量没建起来
            return stats.note() + "（检索退回词面打分）";
        }
        return null;
    }

    @Override
    public List<RetrievedChunk> retrieve(String documentId, String question, int topK) {
        List<LocalScorer.Candidate> candidates = new ArrayList<>();
        for (DocumentChunkEntity chunk : chunkIndexService.chunks(documentId,
                properties.getRetrieval().getCandidateLimit())) {
            candidates.add(new LocalScorer.Candidate(chunk.getId(), chunk.getContent(),
                    chunk.getPageFrom(), chunk.getCharStart(), chunk.getCharEnd()));
        }
        Map<String, Double> vectorScores = vectorService == null
                ? Map.of()
                : vectorService.search(documentId, question, candidates.size());
        List<HybridScorer.Hit> hits = HybridScorer.rank(question, candidates, vectorScores, topK,
                properties.getRetrieval().getVectorWeight(),
                properties.getRetrieval().getVectorMinScore());
        List<RetrievedChunk> result = new ArrayList<>(hits.size());
        for (HybridScorer.Hit hit : hits) {
            result.add(new RetrievedChunk(hit.candidate().id(), documentId, hit.candidate().content(),
                    hit.candidate().page(), hit.candidate().charStart(), hit.candidate().charEnd(),
                    hit.score()));
        }
        return result;
    }
}
