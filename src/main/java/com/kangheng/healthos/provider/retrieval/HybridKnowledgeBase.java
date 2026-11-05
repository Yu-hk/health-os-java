package com.kangheng.healthos.provider.retrieval;

import java.util.ArrayList;
import java.util.List;

import com.kangheng.healthos.model.classics.DocumentChunkEntity;
import com.kangheng.healthos.provider.retrieval.ChunkIndexService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.kangheng.healthos.spi.retrieval.KnowledgeBase;

/**
 * 混合知识库：写两份，检索优先 RAGFlow，失败或空结果回落本地。
 *
 * <p>为什么值得做双写而不是二选一：
 * <ul>
 *   <li>RAGFlow 提供语义召回，但它是外部依赖，故障时会直接让问答不可用；</li>
 *   <li>本地切片表提供<b>原文偏移</b>（charStart/charEnd/page），而 RAGFlow 只返回文本片段。
 *       引用要能定位到"第几页第几字"，就必须有本地这份副本。</li>
 * </ul>
 *
 * <p>因此混合模式的检索流程是：RAGFlow 拿候选 → 用本地切片回填偏移 → 回填失败就保留 null。
 * 绝不为了凑出偏移而猜位置。
 */
public class HybridKnowledgeBase implements KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(HybridKnowledgeBase.class);

    private final LocalKnowledgeBase local;
    private final RagflowKnowledgeBase ragflow;
    private final ChunkIndexService chunkIndexService;

    public HybridKnowledgeBase(LocalKnowledgeBase local,
                               RagflowKnowledgeBase ragflow,
                               ChunkIndexService chunkIndexService) {
        this.local = local;
        this.ragflow = ragflow;
        this.chunkIndexService = chunkIndexService;
    }

    @Override
    public String name() {
        return "hybrid";
    }

    @Override
    public IndexOutcome index(String documentId, String title, String markdown) {
        long started = System.nanoTime();
        IndexOutcome localOutcome = local.index(documentId, title, markdown);
        try {
            IndexOutcome remote = ragflow.index(documentId, title, markdown);
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            return new IndexOutcome("hybrid", Math.max(localOutcome.chunks(), remote.chunks()),
                    remote.externalRef(), elapsed, null);
        } catch (RuntimeException ex) {
            // 外部索引失败不阻断流水线：本地索引已可用，问答仍有依据。
            // 但必须把降级事实写进 note，让 detail 里看得到"这次没进 RAGFlow"。
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            log.warn("RAGFlow 索引失败，已降级为本地索引 doc={}: {}", documentId, ex.getMessage());
            return new IndexOutcome("hybrid(local-only)", localOutcome.chunks(), null, elapsed,
                    "ragflow 索引失败: " + ex.getMessage());
        }
    }

    @Override
    public void delete(String documentId) {
        try {
            ragflow.delete(documentId);
        } catch (RuntimeException ex) {
            log.warn("删除 RAGFlow 索引失败 doc={}: {}", documentId, ex.getMessage());
        }
    }

    @Override
    public List<RetrievedChunk> retrieve(String documentId, String question, int topK) {
        try {
            List<RetrievedChunk> hits = ragflow.retrieve(documentId, question, topK);
            if (!hits.isEmpty()) {
                return enrich(hits, documentId);
            }
            log.info("RAGFlow 检索为空，回落本地检索 doc={}", documentId);
        } catch (RuntimeException ex) {
            log.warn("RAGFlow 检索失败，回落本地 doc={}: {}", documentId, ex.getMessage());
        }
        return local.retrieve(documentId, question, topK);
    }

    @Override
    public boolean healthy() {
        return ragflow.healthy();
    }

    /**
     * 数据集目录与跨库检索只能走 RAGFlow——本地没有"库"这个概念。
     *
     * <p>这里<b>不吞异常</b>：调用方需要区分"RAGFlow 连不上"和"这个库里没有东西"，
     * 把前者包装成空列表会让运维以为知识库是空的。
     */
    @Override
    public List<DatasetInfo> datasets() {
        return ragflow.datasets();
    }

    /**
     * 跨库检索同样要回填本地偏移。
     *
     * <p>患者绑定的库是多文档的，每条的归属不同，所以定位时用<b>每条命中自带的
     * documentId</b>，而不是某一份固定文档——用固定文档去查会查不到，
     * 结果就是引用全都丢了页码。
     */
    @Override
    public List<RetrievedChunk> retrieveDatasets(List<String> datasetIds, String question, int topK) {
        return enrich(ragflow.retrieveDatasets(datasetIds, question, topK), null);
    }

    /**
     * 用本地切片给 RAGFlow 的命中补回原文偏移；补不到就保持 null。
     *
     * @param fallbackDocumentId 单文档检索时已知的文档 ID；跨库检索传 {@code null}，
     *                           此时只认每条命中自带的 {@code documentId}，认不出就留空
     */
    private List<RetrievedChunk> enrich(List<RetrievedChunk> hits, String fallbackDocumentId) {
        List<RetrievedChunk> enriched = new ArrayList<>(hits.size());
        for (RetrievedChunk hit : hits) {
            String documentId = hit.documentId() == null ? fallbackDocumentId : hit.documentId();
            if (documentId == null) {
                // 不知道该用哪份文档的切片去对：保留原命中，页码留空，不猜
                enriched.add(hit);
                continue;
            }
            var located = chunkIndexService.locate(documentId, hit.content());
            if (located.isEmpty()) {
                log.debug("命中片段未能在本地切片定位，引用将不含页码 doc={}", documentId);
                enriched.add(hit);
                continue;
            }
            DocumentChunkEntity chunk = located.get();
            enriched.add(new RetrievedChunk(hit.chunkId(), documentId, hit.content(),
                    chunk.getPageFrom(), chunk.getCharStart(), chunk.getCharEnd(), hit.score()));
        }
        return enriched;
    }
}
