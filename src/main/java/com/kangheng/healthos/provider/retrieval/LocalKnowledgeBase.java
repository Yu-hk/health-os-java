package com.kangheng.healthos.provider.retrieval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kangheng.healthos.model.classics.DocumentChunkEntity;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.spi.retrieval.KnowledgeBase;
import com.kangheng.healthos.spi.retrieval.RetrievalReranker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;import com.kangheng.healthos.provider.retrieval.NoopReranker;

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
 * <p><b>两路召回是对等的</b>：词面只扫位置窗口（前 {@code candidate-limit} 片），
 * 向量那头取相似度 top-N。只把向量分当"给窗口内的切片加权"会让长文档的后半本
 * 永远召不回来，所以向量命中的切片会被按 ID 捞回来并入候选集（见
 * {@link #addDenseOnlyCandidates}）。
 *
 * <p>不标 {@code @Component}：由 {@link com.kangheng.healthos.config.RetrievalConfig}
 * 按 {@code healthos.retrieval.provider} 显式装配，避免容器里出现多个 {@code KnowledgeBase} 候选。
 */
public class LocalKnowledgeBase implements KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(LocalKnowledgeBase.class);

    public static final String ENGINE = "local-chunk-index";

    private final ChunkIndexService chunkIndexService;
    private final ChunkVectorService vectorService;
    private final HealthOsProperties properties;
    private final RetrievalReranker reranker;

    public LocalKnowledgeBase(ChunkIndexService chunkIndexService,
                              ChunkVectorService vectorService,
                              HealthOsProperties properties) {
        this(chunkIndexService, vectorService, properties, NoopReranker.INSTANCE);
    }

    public LocalKnowledgeBase(ChunkIndexService chunkIndexService,
                              ChunkVectorService vectorService,
                              HealthOsProperties properties,
                              RetrievalReranker reranker) {
        this.chunkIndexService = chunkIndexService;
        this.vectorService = vectorService;
        this.properties = properties;
        this.reranker = reranker == null ? NoopReranker.INSTANCE : reranker;
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
        int candidateLimit = properties.getRetrieval().getCandidateLimit();
        List<LocalScorer.Candidate> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (DocumentChunkEntity chunk : chunkIndexService.chunks(documentId, candidateLimit)) {
            seen.add(chunk.getId());
            candidates.add(toCandidate(chunk));
        }
        // 向量检索取 top-N；N 用 candidate-limit，和词面窗口一样大，这样两路召回是对等的
        Map<String, Double> vectorScores = vectorService == null
                ? Map.of()
                : vectorService.search(documentId, question, candidateLimit);
        addDenseOnlyCandidates(documentId, vectorScores, seen, candidates);
        // 重排开启且可用时，融合序先多取一批候选送 cross-encoder；否则按 topK 截断即可
        boolean rerankOn = reranker.available();
        int fusionTopK = rerankOn
                ? Math.max(topK, properties.getRetrieval().getRerankCandidateLimit())
                : topK;
        List<HybridScorer.Hit> hits = HybridScorer.rank(question, candidates, vectorScores, fusionTopK,
                properties.getRetrieval().getVectorWeight(),
                properties.getRetrieval().getVectorMinScore());
        if (rerankOn) {
            hits = applyRerank(question, hits, topK);
        }
        List<RetrievedChunk> result = new ArrayList<>(hits.size());
        for (HybridScorer.Hit hit : hits) {
            result.add(new RetrievedChunk(hit.candidate().id(), documentId, hit.candidate().content(),
                    hit.candidate().page(), hit.candidate().charStart(), hit.candidate().charEnd(),
                    hit.score()));
        }
        return result;
    }

    /**
     * 用 cross-encoder 对融合序头部候选重排。
     *
     * <p><b>任何异常都退回融合序</b>——重排是增强不是依赖。端点挂了、超时、模型没加载、
     * 返回格式不对，都只记日志，问答照样用融合序出结果。
     *
     * @return 重排后的 hit 列表（按相关性降序，已截断到 {@code topK}）；降级时原样返回 {@code fused}
     */
    private List<HybridScorer.Hit> applyRerank(String question, List<HybridScorer.Hit> fused, int topK) {
        int keep = properties.getRetrieval().getRerankTopK() > 0
                ? properties.getRetrieval().getRerankTopK() : topK;
        if (fused.isEmpty()) {
            return fused;
        }
        try {
            List<RetrievalReranker.Passage> passages = new ArrayList<>(fused.size());
            for (HybridScorer.Hit hit : fused) {
                passages.add(new RetrievalReranker.Passage(hit.candidate().id(), hit.candidate().content()));
            }
            List<RetrievalReranker.RankedPassage> ranked = reranker.rerank(question, passages);
            Map<String, HybridScorer.Hit> byId = new LinkedHashMap<>();
            for (HybridScorer.Hit hit : fused) {
                byId.put(hit.candidate().id(), hit);
            }
            List<HybridScorer.Hit> reordered = new ArrayList<>(ranked.size());
            for (RetrievalReranker.RankedPassage rp : ranked) {
                HybridScorer.Hit hit = byId.get(rp.chunkId());
                if (hit != null) {
                    reordered.add(hit);
                }
            }
            // 重排只返回了部分候选（允许截断）：剩下的融合序尾部补在后面，保证不丢引用
            if (reordered.size() < fused.size()) {
                for (HybridScorer.Hit hit : fused) {
                    if (!reordered.contains(hit)) {
                        reordered.add(hit);
                    }
                }
            }
            return reordered.size() <= keep ? reordered : new ArrayList<>(reordered.subList(0, keep));
        } catch (Exception ex) {
            log.warn("重排失败，退回融合序: {}", ex.getMessage());
            return fused.size() <= keep ? fused : new ArrayList<>(fused.subList(0, keep));
        }
    }

    /**
     * 把「向量命中、但没进词面窗口」的切片补进候选集。
     *
     * <p>这是长文档能召回的关键。{@link ChunkIndexService#chunks} 按 {@code chunk_index} 取前
     * {@code candidate-limit} 片——一本 2 万片的典籍，词面只看得到最前面那 200 片。
     * 只把向量分当"给这 200 片加权"用，等于长文档的后 99% 永远无法被召回；
     * 补进候选集之后，向量才真正成为一条独立的召回路径。
     *
     * <p>补进来的是**库里真实的切片**（正文、页码、字符偏移都在），不是凭空拼的文本，
     * 所以引用定位与词面命中的切片没有区别。
     */
    private void addDenseOnlyCandidates(String documentId, Map<String, Double> vectorScores,
                                        Set<String> seen, List<LocalScorer.Candidate> candidates) {
        if (vectorScores.isEmpty() || chunkIndexService == null) {
            return;
        }
        List<String> extra = new ArrayList<>();
        for (String chunkId : vectorScores.keySet()) {
            if (!seen.contains(chunkId)) {
                extra.add(chunkId);
            }
        }
        if (extra.isEmpty()) {
            return;
        }
        for (DocumentChunkEntity chunk : chunkIndexService.byIds(extra)) {
            // 防御：库里被删掉但向量还在的孤儿行，捞不回来就不该出现在候选里
            if (documentId.equals(chunk.getDocumentId()) && seen.add(chunk.getId())) {
                candidates.add(toCandidate(chunk));
            }
        }
    }

    private static LocalScorer.Candidate toCandidate(DocumentChunkEntity chunk) {
        return new LocalScorer.Candidate(chunk.getId(), chunk.getContent(),
                chunk.getPageFrom(), chunk.getCharStart(), chunk.getCharEnd());
    }
}
