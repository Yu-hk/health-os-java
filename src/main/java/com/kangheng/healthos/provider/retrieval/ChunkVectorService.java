package com.kangheng.healthos.provider.retrieval;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.mapper.classics.DocumentChunkMapper;
import com.kangheng.healthos.mapper.retrieval.ChunkVectorMapper;
import com.kangheng.healthos.model.classics.DocumentChunkEntity;
import com.kangheng.healthos.model.retrieval.ChunkVectorEntity;
import com.kangheng.healthos.model.retrieval.ChunkVectorScore;
import com.kangheng.healthos.spi.ai.EmbeddingException;
import com.kangheng.healthos.spi.ai.EmbeddingModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 切片向量的落库与查询。
 *
 * <p>两个入口，一个目的：
 * <ul>
 *   <li>{@link #indexDocument} —— 索引期（流水线 index 节点）把整篇切片向量化；</li>
 *   <li>{@link #search} —— 检索期把问题向量化，取回本文档最相近的切片。</li>
 * </ul>
 *
 * <p><b>两条检索路径，一份语义</b>：库里有 pgvector 就走 ANN（{@code ORDER BY embedding <=> q LIMIT n}），
 * 否则把整篇的 JSON 向量取回内存在 Java 侧算余弦。两条路返回的都是 cosine 相似度、
 * 都截断到 {@code [0,1]}，所以融合排序不需要知道分数是怎么来的。
 * 差异只在"单次检索要搬多少数据"：ANN 只搬 top-N，另一条要搬全文档的向量。
 *
 * <p><b>降级是这里的核心语义</b>：Ollama 挂了不能让索引失败，也不能让问答失败。
 * 索引期失败写 {@code note} 上报（流水线节点 detail 里能看到），检索期失败返回空
 * 映射，调用方自动退回词面打分。但**每次都重试一个已经挂掉的服务**同样是故障，
 * 所以失败后会安静 {@code RETRY_COOLDOWN_MS}，冷却期内不再尝试。
 *
 * <p><b>为什么查询时还会写库</b>：历史文档是在加向量之前索引的。首次检索到它就顺手补建，
 * 比让运维记得"上线后跑一次回填"更可靠——没人记得。补建带 in-flight 去重，
 * 同一篇文档并发首查只会有一路真的去算。
 */
@Service
public class ChunkVectorService {

    private static final Logger log = LoggerFactory.getLogger(ChunkVectorService.class);

    /** embedding 挂了之后的冷却时间：既不每次重试，也不永久放弃。 */
    private static final long RETRY_COOLDOWN_MS = 60_000L;

    private final ChunkVectorMapper vectorMapper;
    private final DocumentChunkMapper chunkMapper;
    private final EmbeddingModel embedding;
    private final PgVectorIndex index;
    private final HealthOsProperties.Retrieval config;

    /** 正在补建向量的文档，避免并发首查重复计算。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicLong blockedUntil = new AtomicLong(0);

    public ChunkVectorService(ChunkVectorMapper vectorMapper,
                              DocumentChunkMapper chunkMapper,
                              EmbeddingModel embedding,
                              PgVectorIndex index,
                              HealthOsProperties properties) {
        this.vectorMapper = vectorMapper;
        this.chunkMapper = chunkMapper;
        this.embedding = embedding;
        this.index = index;
        this.config = properties.getRetrieval();
    }

    public boolean enabled() {
        return embedding.enabled();
    }

    public String modelId() {
        return embedding.id();
    }

    public int dimension() {
        return embedding.dimension();
    }

    /** 当前是否走 ANN 索引；健康检查与流水线 detail 用它说明"检索是怎么走的"。 */
    public boolean annEnabled() {
        return index != null && index.available();
    }

    public String indexPosture() {
        return index == null ? "disabled:none" : index.posture();
    }

    /**
     * 把某文档的全部切片向量化。<b>幂等</b>：正文没变的切片不重算，切片被删掉的向量一起清。
     *
     * @return 本次实际写入的条数与总数；失败时 {@link Stats#note()} 非空
     */
    public Stats indexDocument(String documentId) {
        if (!enabled()) {
            return new Stats(0, 0, embedding.id(), null);
        }
        List<DocumentChunkEntity> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunkEntity>()
                        .eq(DocumentChunkEntity::getDocumentId, documentId)
                        .orderByAsc(DocumentChunkEntity::getChunkIndex));
        if (chunks.isEmpty()) {
            return new Stats(0, 0, embedding.id(), null);
        }
        Map<String, ChunkVectorEntity> existing = new HashMap<>();
        for (ChunkVectorEntity row : vectorMapper.listByDocument(documentId, embedding.id())) {
            existing.put(row.getChunkId(), row);
        }
        // 有文本向量但没有定长向量的行也必须重写，否则它们永远进不了 ANN 查询
        Set<String> missingEmbedding = annEnabled()
                ? new HashSet<>(vectorMapper.listMissingEmbedding(documentId, embedding.id()))
                : Set.of();
        Set<String> currentIds = new HashSet<>();
        List<DocumentChunkEntity> todo = new ArrayList<>();
        for (DocumentChunkEntity chunk : chunks) {
            currentIds.add(chunk.getId());
            ChunkVectorEntity row = existing.get(chunk.getId());
            boolean stale = row == null || !Ids.sha256Hex(chunk.getContent()).equals(row.getContentHash());
            if (stale || missingEmbedding.contains(chunk.getId())) {
                todo.add(chunk);
            }
        }
        // 切片被重切后 chunkId 会变，旧行不清会一直占着名额并参与相似度计算
        removeStale(documentId, currentIds);
        if (todo.isEmpty()) {
            return new Stats(0, chunks.size(), embedding.id(), null);
        }
        try {
            List<String> texts = todo.stream().map(DocumentChunkEntity::getContent).toList();
            List<float[]> vectors = embedding.embedBatch(texts);
            if (vectors.size() != texts.size()) {
                return new Stats(0, chunks.size(), embedding.id(),
                        "embedding_count_mismatch:" + vectors.size() + "/" + texts.size());
            }
            OffsetDateTime now = OffsetDateTime.now();
            for (int i = 0; i < todo.size(); i++) {
                DocumentChunkEntity chunk = todo.get(i);
                ChunkVectorEntity row = new ChunkVectorEntity();
                row.setId(Ids.stableId("cv", chunk.getId(), embedding.id()));
                row.setChunkId(chunk.getId());
                row.setDocumentId(documentId);
                row.setModel(embedding.id());
                row.setDimension(vectors.get(i).length);
                row.setVector(Json.write(vectors.get(i)));
                row.setContentHash(Ids.sha256Hex(chunk.getContent()));
                row.setCreatedAt(now);
                write(row);
            }
            blockedUntil.set(0);
            log.info("切片向量已建立 documentId={} model={} 写入={}/{} ann={}", documentId, embedding.id(),
                    todo.size(), chunks.size(), annEnabled());
            return new Stats(todo.size(), chunks.size(), embedding.id(), null);
        } catch (EmbeddingException ex) {
            block(ex);
            log.warn("切片向量化失败，检索将退回词面打分 documentId={} code={} msg={}",
                    documentId, ex.code(), ex.getMessage());
            return new Stats(0, chunks.size(), embedding.id(), "embedding_degraded:" + ex.code());
        } catch (RuntimeException ex) {
            block(new EmbeddingException("EMBEDDING_UNEXPECTED", ex.getMessage(), true, ex));
            log.warn("切片向量化异常，检索将退回词面打分 documentId={}", documentId, ex);
            return new Stats(0, chunks.size(), embedding.id(), "embedding_degraded:unexpected");
        }
    }

    /**
     * 落一行。ANN 可用时同时写定长向量列；写失败就降级回纯文本路径，
     * 而不是让整篇文档索引失败。
     */
    private void write(ChunkVectorEntity row) {
        if (annEnabled()) {
            try {
                vectorMapper.upsertWithEmbedding(row);
                return;
            } catch (DataAccessException ex) {
                // 典型原因：维度与列定义不符、扩展被卸掉。降级总比整篇索引失败好
                index.degrade("write_failed:" + dbReason(ex));
            }
        }
        // 唯一键 (chunk_id, model) 保证重放不会写出两行
        vectorMapper.delete(new LambdaQueryWrapper<ChunkVectorEntity>()
                .eq(ChunkVectorEntity::getChunkId, row.getChunkId())
                .eq(ChunkVectorEntity::getModel, row.getModel()));
        vectorMapper.insert(row);
    }

    /**
     * 问题与本文档各切片的余弦相似度，取最相近的前 {@code limit} 条。
     *
     * <p>返回条数上限是 {@code limit} 而不是全部切片：pgvector 路径本来就只有 top-N，
     * 两条路径必须给出同一形状的结果，否则"召回变好了还是变差了"没法比较。
     *
     * @return chunkId → [0,1]；未启用、未索引、上游失败均返回空映射（调用方退回词面打分）
     */
    public Map<String, Double> search(String documentId, String question, int limit) {
        if (!enabled() || question == null || question.isBlank()) {
            return Map.of();
        }
        if (System.currentTimeMillis() < blockedUntil.get()) {
            return Map.of();
        }
        boolean ann = annEnabled();
        List<ChunkVectorEntity> rows = ann ? List.of() : vectorMapper.listByDocument(documentId, embedding.id());
        if (!ann && rows.isEmpty() && ensureBackfill(documentId)) {
            rows = vectorMapper.listByDocument(documentId, embedding.id());
        }
        if (!ann && rows.isEmpty()) {
            return Map.of();
        }
        float[] query;
        try {
            query = embedding.embed(question);
        } catch (EmbeddingException ex) {
            block(ex);
            log.warn("问题向量化失败，本次检索退回词面打分 code={} msg={}", ex.code(), ex.getMessage());
            return Map.of();
        }
        if (query.length == 0) {
            return Map.of();
        }
        if (ann) {
            Map<String, Double> result = searchByIndex(documentId, query, limit);
            if (!result.isEmpty()) {
                return result;
            }
            // ANN 空结果（历史行还没回填定长向量）时退回全量，别让"索引晚于数据"变成"检索没结果"
            rows = vectorMapper.listByDocument(documentId, embedding.id());
            if (rows.isEmpty() && ensureBackfill(documentId)) {
                rows = vectorMapper.listByDocument(documentId, embedding.id());
            }
        }
        return cosineInMemory(query, rows, documentId);
    }

    private Map<String, Double> searchByIndex(String documentId, float[] query, int limit) {
        try {
            List<ChunkVectorScore> hits = vectorMapper.searchTopK(documentId, embedding.id(),
                    Json.write(query), Math.max(1, limit));
            Map<String, Double> scores = new HashMap<>(hits.size());
            for (ChunkVectorScore hit : hits) {
                if (hit.getScore() == null) {
                    continue;
                }
                scores.put(hit.getChunkId(), clamp(hit.getScore()));
            }
            return scores;
        } catch (DataAccessException ex) {
            index.degrade("query_failed:" + dbReason(ex));
            return Map.of();
        }
    }

    private Map<String, Double> cosineInMemory(float[] query, List<ChunkVectorEntity> rows, String documentId) {
        Map<String, Double> scores = new HashMap<>(rows.size());
        int skipped = 0;
        for (ChunkVectorEntity row : rows) {
            float[] vector;
            try {
                vector = Json.read(row.getVector(), float[].class);
            } catch (RuntimeException ex) {
                skipped++;
                continue;
            }
            if (vector == null || vector.length != query.length) {
                // 换了模型/维度：旧向量直接出局，不猜、不补零
                skipped++;
                continue;
            }
            scores.put(row.getChunkId(), clamp(EmbeddingModel.cosine(query, vector)));
        }
        if (skipped > 0) {
            log.debug("检索时跳过 {} 条维度不符/损坏的向量 documentId={}", skipped, documentId);
        }
        return scores;
    }

    private static double clamp(double cosine) {
        return Math.max(0d, Math.min(1d, cosine));
    }

    /** 从 DataAccessException 里取一句能写进降级原因的话；异常 mock 掉 getMostSpecificCause 时也不崩。 */
    private static String dbReason(DataAccessException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause == null) {
            cause = ex;
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }

    /** 历史文档首次被检索时补建向量；同一文档并发首查只让一路去算。 */
    private boolean ensureBackfill(String documentId) {
        if (!inFlight.add(documentId)) {
            return false;
        }
        try {
            Stats stats = indexDocument(documentId);
            return stats.embedded() > 0;
        } finally {
            inFlight.remove(documentId);
        }
    }

    private void removeStale(String documentId, Set<String> currentIds) {
        List<ChunkVectorEntity> existing = vectorMapper.listByDocument(documentId, embedding.id());
        for (ChunkVectorEntity row : existing) {
            if (!currentIds.contains(row.getChunkId())) {
                vectorMapper.deleteById(row.getId());
            }
        }
    }

    private void block(EmbeddingException ex) {
        if (ex.retryable()) {
            blockedUntil.set(System.currentTimeMillis() + RETRY_COOLDOWN_MS);
        }
    }

    /**
     * @param embedded 本次写入条数（0 表示无需重算或失败）
     * @param total    该文档切片总数
     * @param note     非空即降级，会写进流水线节点 detail
     */
    public record Stats(int embedded, int total, String model, String note) {
    }
}
