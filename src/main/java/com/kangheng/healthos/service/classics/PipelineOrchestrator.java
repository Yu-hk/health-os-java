package com.kangheng.healthos.service.classics;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.kangheng.healthos.spi.graph.MergedGraph;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.mapper.classics.ClassicDocumentMapper;
import com.kangheng.healthos.mapper.classics.ClassicJobMapper;
import com.kangheng.healthos.mapper.classics.ExtractionCheckpointMapper;
import com.kangheng.healthos.model.classics.ArtifactEntity;
import com.kangheng.healthos.model.classics.ClassicDocument;
import com.kangheng.healthos.model.classics.ClassicJob;
import com.kangheng.healthos.model.classics.ExtractionCheckpointEntity;
import com.kangheng.healthos.model.classics.PipelineNodeEntity;
import com.kangheng.healthos.model.classics.PipelineNodeSpec;
import com.kangheng.healthos.model.classics.Segment;
import com.kangheng.healthos.service.classics.DocumentNormalizer;
import com.kangheng.healthos.service.classics.ExtractionMerger;
import com.kangheng.healthos.text.TextSegmenter;
import com.kangheng.healthos.service.graph.GraphService;
import com.kangheng.healthos.service.tracing.TracingService;
import com.kangheng.healthos.spi.ai.LlmException;
import com.kangheng.healthos.spi.ai.LlmProvider;
import com.kangheng.healthos.spi.graph.GraphStore;
import com.kangheng.healthos.spi.retrieval.KnowledgeBase;
import com.kangheng.healthos.spi.storage.ObjectStorage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 典籍解析流水线编排。
 *
 * <p>与原项目 {@code run_pipeline} 对齐的核心行为：
 * <ul>
 *   <li>五个节点顺序执行，每个节点成功即可复用（重跑跳过已完成节点）；</li>
 *   <li>抽取按分段落检查点，命中检查点不再调用模型；</li>
 *   <li>体量敏感错误二分重跑，普通错误指数退避，不可重试错误立即失败；</li>
 *   <li>失败时把错误写回当前节点、文档与任务三处，保留已完成节点的成果。</li>
 * </ul>
 */
@Service
public class PipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);
    private static final String GRAPH_SCHEMA_VERSION = "healthos-java.graph.1";

    private final ClassicDocumentMapper documentMapper;
    private final ClassicJobMapper jobMapper;
    private final ExtractionCheckpointMapper checkpointMapper;
    private final PipelineService pipelineService;
    private final DocumentNormalizer normalizer;
    private final KnowledgeBase knowledgeBase;
    private final GraphService graphService;
    private final LlmProvider llmProvider;
    private final ObjectStorage storage;
    private final HealthOsProperties properties;
    private final TracingService tracing;

    public PipelineOrchestrator(ClassicDocumentMapper documentMapper,
                                ClassicJobMapper jobMapper,
                                ExtractionCheckpointMapper checkpointMapper,
                                PipelineService pipelineService,
                                DocumentNormalizer normalizer,
                                KnowledgeBase knowledgeBase,
                                GraphService graphService,
                                LlmProvider llmProvider,
                                ObjectStorage storage,
                                HealthOsProperties properties,
                                TracingService tracing) {
        this.documentMapper = documentMapper;
        this.jobMapper = jobMapper;
        this.checkpointMapper = checkpointMapper;
        this.pipelineService = pipelineService;
        this.normalizer = normalizer;
        this.knowledgeBase = knowledgeBase;
        this.graphService = graphService;
        this.llmProvider = llmProvider;
        this.storage = storage;
        this.properties = properties;
        this.tracing = tracing;
    }

    /** 一次执行的上下文，用于失败时回写当前节点。 */
    private static final class RunContext {
        ClassicDocument document;
        ClassicJob job;
        PipelineNodeEntity current;
    }

    private record NormalizeOutcome(String markdown, String artifactId, int pageCount, String sourceKind,
                                    String engine, int ocrPages) {
    }

    private record GraphPayload(String schemaVersion, List<LlmProvider.ExtractedEntity> entities,
                                List<LlmProvider.ExtractedRelation> relations, int segmentCount) {
    }

    public void run(String documentId, String jobId) {
        ClassicDocument document = documentMapper.selectById(documentId);
        ClassicJob job = jobMapper.selectById(jobId);
        if (document == null || job == null) {
            throw BizException.notFound("文档或任务");
        }
        pipelineService.initializeNodes(documentId, jobId);

        RunContext ctx = new RunContext();
        ctx.document = document;
        ctx.job = job;

        job.setStatus("RUNNING");
        job.setAttempt((job.getAttempt() == null ? 0 : job.getAttempt()) + 1);
        job.setStartedAt(OffsetDateTime.now());
        job.setCompletedAt(null);
        job.setErrorCode(null);
        job.setErrorMessage(null);
        jobMapper.updateById(job);
        pipelineService.markDocument(documentId, "PROCESSING", document.getCurrentNode(), null, null);

        log.info("流水线开始 documentId={} jobId={} attempt={} provider={}",
                documentId, jobId, job.getAttempt(), llmProvider.name());
        // 流水线是异步跑的，本身没有登录态；但文档记着上传者，
        // 所以轨迹归属还原到真正发起这次处理的人，而不是一个没有意义的 "system"。
        String traceOwner = document.getCreatedBy() == null ? "system" : document.getCreatedBy();
        tracing.beginTrace("classic_document", documentId, traceOwner, null);
        tracing.event("pipeline_started", Map.of("jobId", jobId, "attempt", job.getAttempt(),
                "provider", llmProvider.name()));
        try {
            NormalizeOutcome normalized = tracing.span("normalize_or_ocr", "business_stage", "pipeline",
                    () -> runNormalize(ctx));
            GraphPayload payload = tracing.span("tcm_extract", "business_stage", "pipeline",
                    () -> runExtract(ctx, normalized));
            int chunkCount = tracing.span("chunk_index", "retrieval", "knowledge-base",
                    () -> runChunkIndex(ctx, normalized));
            GraphStore.PersistResult persisted = tracing.span("graph_build", "graph", "graph-store",
                    () -> runGraphBuild(ctx, normalized, payload));
            tracing.event("pipeline_counts", Map.of("chunks", chunkCount,
                    "entities", persisted.entities(), "relations", persisted.relations(),
                    "evidences", persisted.evidences(), "droppedRelations", persisted.droppedRelations(),
                    "graphStore", graphService.storeName()));
            tracing.span("manifest", "business_stage", "pipeline", () -> {
                runManifest(ctx, normalized, payload, chunkCount, persisted);
                return null;
            });

            pipelineService.markDocument(documentId, "READY", null, null, null);
            pipelineService.markJob(jobId, "SUCCEEDED", null, null);
            tracing.finishTrace("SUCCEEDED");
            log.info("流水线完成 documentId={} entities={} relations={} chunks={}",
                    documentId, persisted.entities(), persisted.relations(), chunkCount);
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            if (ctx.current != null) {
                pipelineService.fail(ctx.current, message);
            }
            String code = ex instanceof LlmException ? "LLM_FAILED"
                    : ex instanceof BizException biz ? biz.getCode() : "PIPELINE_FAILED";
            pipelineService.markDocument(documentId, "FAILED", ctx.current == null ? null : ctx.current.getCode(),
                    code, message);
            pipelineService.markJob(jobId, "FAILED", code, message);
            tracing.finishTrace("FAILED", code, message);
            log.error("流水线失败 documentId={} jobId={} node={}",
                    documentId, jobId, ctx.current == null ? "-" : ctx.current.getCode(), ex);
            throw ex instanceof RuntimeException runtime ? runtime : new IllegalStateException(message, ex);
        }
    }

    // ---------------------------------------------------------------- 节点 1

    private NormalizeOutcome runNormalize(RunContext ctx) {
        PipelineNodeEntity node = pipelineService.node(ctx.job.getId(), PipelineNodeSpec.NORMALIZE_OR_OCR);
        ctx.current = node;
        Map<String, Object> detail = pipelineService.detail(node);
        if (pipelineService.reusable(node) && detail.get("artifact_id") != null) {
            String artifactId = String.valueOf(detail.get("artifact_id"));
            String markdown = new String(pipelineService.readArtifact(artifactId), StandardCharsets.UTF_8);
            log.info("归一化节点命中检查点，跳过 doc={}", ctx.document.getId());
            return new NormalizeOutcome(markdown, artifactId,
                    intOf(detail.get("page_count")), String.valueOf(detail.getOrDefault("source_kind", "text")),
                    String.valueOf(detail.getOrDefault("engine", "unknown")), intOf(detail.get("ocr_pages")));
        }

        pipelineService.start(node, PipelineNodeSpec.NORMALIZE_OR_OCR);
        byte[] raw = storage.get(ctx.document.getSourceObjectKey());
        DocumentNormalizer.Normalized normalized =
                normalizer.normalize(raw, ctx.document.getContentType(), ctx.document.getTitle());
        byte[] markdownBytes = normalized.markdown().getBytes(StandardCharsets.UTF_8);
        ArtifactEntity artifact = pipelineService.storeArtifact(ctx.document.getId(), ctx.job.getId(),
                "MARKDOWN", markdownBytes, "md", "text/markdown");
        pipelineService.finish(node, 1.0, pipelineService.detailOf(
                "artifact_id", artifact.getId(),
                "page_count", normalized.pageCount(),
                "source_kind", normalized.sourceKind(),
                "engine", normalized.engine(),
                "ocr_pages", normalized.ocrPages(),
                "chars", normalized.markdown().length()));
        return new NormalizeOutcome(normalized.markdown(), artifact.getId(),
                normalized.pageCount(), normalized.sourceKind(), normalized.engine(), normalized.ocrPages());
    }

    // ---------------------------------------------------------------- 节点 2

    private GraphPayload runExtract(RunContext ctx, NormalizeOutcome normalized) {
        PipelineNodeEntity node = pipelineService.node(ctx.job.getId(), PipelineNodeSpec.TCM_EXTRACT);
        ctx.current = node;
        Map<String, Object> detail = pipelineService.detail(node);
        if (pipelineService.reusable(node) && detail.get("artifact_id") != null) {
            String artifactId = String.valueOf(detail.get("artifact_id"));
            GraphPayload payload = Json.read(
                    new String(pipelineService.readArtifact(artifactId), StandardCharsets.UTF_8),
                    GraphPayload.class);
            if (payload != null) {
                log.info("抽取节点命中检查点，跳过 doc={}", ctx.document.getId());
                return payload;
            }
        }

        pipelineService.start(node, PipelineNodeSpec.TCM_EXTRACT);
        HealthOsProperties.Extraction config = properties.getExtraction();
        List<Segment> segments = TextSegmenter.segment(
                normalized.markdown(), config.getChunkChars(), config.getChunkOverlap());
        if (segments.isEmpty()) {
            throw BizException.badRequest("文档归一化后为空，无法抽取");
        }

        List<LlmProvider.ExtractionResult> results = new ArrayList<>();
        int completed = 0;
        for (Segment segment : segments) {
            results.addAll(extractSegment(ctx, segment, 0));
            completed++;
            double ratio = 0.05 + 0.9 * completed / segments.size();
            pipelineService.progress(node, ratio,
                    pipelineService.detailOf("completed", completed, "total", segments.size()));
        }

        MergedGraph merged = ExtractionMerger.merge(results,
                config.getMinConfidence(),
                config.getMaxEntitiesPerSegment() * Math.max(1, segments.size()),
                config.getMaxRelationsPerSegment() * Math.max(1, segments.size()));

        GraphPayload payload = new GraphPayload(GRAPH_SCHEMA_VERSION,
                merged.entities(), merged.relations(), segments.size());
        ArtifactEntity artifact = pipelineService.storeArtifact(ctx.document.getId(), ctx.job.getId(),
                "EXTRACTION", Json.write(payload).getBytes(StandardCharsets.UTF_8), "json", "application/json");
        pipelineService.finish(node, 1.0, pipelineService.detailOf(
                "artifact_id", artifact.getId(),
                "segment_count", segments.size(),
                "entities", merged.entities().size(),
                "relations", merged.relations().size(),
                "provider", llmProvider.name()));
        return payload;
    }

    /** 单分段抽取：检查点 → 重试 → 二分。递归深度受 maxSplitDepth 限制。 */
    private List<LlmProvider.ExtractionResult> extractSegment(RunContext ctx, Segment segment, int depth) {
        String key = segment.checkpointKey();
        Optional<ExtractionCheckpointEntity> cached = checkpoint(ctx.document.getId(), key);
        if (cached.isPresent()) {
            LlmProvider.ExtractionResult stored = Json.read(cached.get().getPayload(),
                    LlmProvider.ExtractionResult.class);
            if (stored != null) {
                return List.of(stored);
            }
        }

        HealthOsProperties.Extraction config = properties.getExtraction();
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                // 每次尝试一个 span：重试与二分是这条链路最贵的部分，必须能按次数看耗时。
                final int currentAttempt = attempt;
                LlmProvider.ExtractionResult result = tracing.span("llm.extract", "model", "llm",
                        Map.of("provider", llmProvider.name(), "chars", segment.text().length(),
                                "attempt", currentAttempt, "depth", depth),
                        () -> llmProvider.extract(
                                LlmProvider.RouteContext.of("MEDICAL_CLASSIC", "classic_document",
                                        ctx.document.getId()),
                                ctx.document.getTitle(), segment.text()));
                saveCheckpoint(ctx, key, result);
                return List.of(result);
            } catch (LlmException ex) {
                RetryPolicy.Decision decision = RetryPolicy.decide(
                        attempt,
                        ex.isRetryable(),
                        ex.isSizeSensitive(),
                        config.getSegmentMaxAttempts(),
                        config.getRetryBase().toMillis(),
                        config.getRetryMax().toMillis(),
                        depth,
                        config.getMaxSplitDepth());
                if (decision.action() == RetryPolicy.Action.SPLIT) {
                    Optional<List<Segment>> halves = TextSegmenter.bisect(segment, config.getSplitMinChars());
                    if (halves.isEmpty()) {
                        throw ex;
                    }
                    log.warn("分段二分重跑 depth={} range=[{}, {})", depth, segment.charStart(), segment.charEnd());
                    List<LlmProvider.ExtractionResult> results = new ArrayList<>();
                    for (Segment half : halves.get()) {
                        results.addAll(extractSegment(ctx, half, depth + 1));
                    }
                    return results;
                }
                if (decision.action() == RetryPolicy.Action.FAIL) {
                    throw ex;
                }
                long delay = RetryPolicy.jittered(decision.delayMillis(), config.getRetryJitterRatio());
                sleep(delay);
            }
        }
    }

    private void saveCheckpoint(RunContext ctx, String key, LlmProvider.ExtractionResult result) {
        ExtractionCheckpointEntity entity = new ExtractionCheckpointEntity();
        entity.setId(Ids.stableId("ckp", ctx.document.getId(), key));
        entity.setDocumentId(ctx.document.getId());
        entity.setJobId(ctx.job.getId());
        entity.setSegmentKey(key);
        entity.setPayload(Json.write(result));
        entity.setCreatedAt(OffsetDateTime.now());
        try {
            checkpointMapper.insert(entity);
        } catch (DuplicateKeyException ex) {
            log.debug("检查点已存在，忽略重复写入 key={}", key);
        }
    }

    private Optional<ExtractionCheckpointEntity> checkpoint(String documentId, String key) {
        return Optional.ofNullable(checkpointMapper.selectOne(
                new LambdaQueryWrapper<ExtractionCheckpointEntity>()
                        .eq(ExtractionCheckpointEntity::getDocumentId, documentId)
                        .eq(ExtractionCheckpointEntity::getSegmentKey, key)));
    }

    // ---------------------------------------------------------------- 节点 3 / 4 / 5

    private int runChunkIndex(RunContext ctx, NormalizeOutcome normalized) {
        PipelineNodeEntity node = pipelineService.node(ctx.job.getId(), PipelineNodeSpec.RAGFLOW_INDEX);
        ctx.current = node;
        Map<String, Object> detail = pipelineService.detail(node);
        if (pipelineService.reusable(node)) {
            return intOf(detail.get("chunks"));
        }
        pipelineService.start(node, PipelineNodeSpec.RAGFLOW_INDEX);
        KnowledgeBase.IndexOutcome outcome = knowledgeBase.index(
                ctx.document.getId(), ctx.document.getTitle(), normalized.markdown());
        Map<String, Object> detailMap = new LinkedHashMap<>(pipelineService.detailOf(
                "chunks", outcome.chunks(),
                "engine", outcome.engine(),
                "provider", knowledgeBase.name(),
                "external_ref", outcome.externalRef(),
                "latency_ms", outcome.latencyMs()));
        if (outcome.note() != null) {
            detailMap.put("degraded", outcome.note());
        }
        pipelineService.finish(node, 1.0, detailMap);
        return outcome.chunks();
    }

    private GraphStore.PersistResult runGraphBuild(RunContext ctx, NormalizeOutcome normalized,
                                                   GraphPayload payload) {
        PipelineNodeEntity node = pipelineService.node(ctx.job.getId(), PipelineNodeSpec.GRAPH_BUILD);
        ctx.current = node;
        Map<String, Object> detail = pipelineService.detail(node);
        if (pipelineService.reusable(node)) {
            return new GraphStore.PersistResult(intOf(detail.get("entities")),
                    intOf(detail.get("relations")), intOf(detail.get("evidences")),
                    intOf(detail.get("dropped_relations")));
        }
        pipelineService.start(node, PipelineNodeSpec.GRAPH_BUILD);
        MergedGraph merged = new MergedGraph(
                payload.entities(), payload.relations());
        GraphStore.PersistResult result = graphService.rebuild(
                ctx.document.getId(), normalized.artifactId(), normalized.markdown(), merged);
        Map<String, Object> detailMap = new LinkedHashMap<>(pipelineService.detailOf(
                "entities", result.entities(),
                "relations", result.relations(),
                "evidences", result.evidences(),
                "dropped_relations", result.droppedRelations(),
                "store", graphService.storeName()));
        if (result.note() != null) {
            detailMap.put("degraded", result.note());
        }
        pipelineService.finish(node, 1.0, detailMap);
        return result;
    }

    private void runManifest(RunContext ctx, NormalizeOutcome normalized, GraphPayload payload,
                             int chunkCount, GraphStore.PersistResult persisted) {
        PipelineNodeEntity node = pipelineService.node(ctx.job.getId(), PipelineNodeSpec.PIPELINE_MANIFEST);
        ctx.current = node;
        if (pipelineService.reusable(node)) {
            return;
        }
        pipelineService.start(node, PipelineNodeSpec.PIPELINE_MANIFEST);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("document_id", ctx.document.getId());
        manifest.put("job_id", ctx.job.getId());
        manifest.put("title", ctx.document.getTitle());
        manifest.put("sha256", ctx.document.getSha256());
        manifest.put("schema_version", GRAPH_SCHEMA_VERSION);
        manifest.put("provider", llmProvider.name());
        manifest.put("page_count", normalized.pageCount());
        manifest.put("segment_count", payload.segmentCount());
        manifest.put("chunk_count", chunkCount);
        manifest.put("entities", persisted.entities());
        manifest.put("relations", persisted.relations());
        manifest.put("evidences", persisted.evidences());
        manifest.put("dropped_relations", persisted.droppedRelations());
        ArtifactEntity artifact = pipelineService.storeArtifact(ctx.document.getId(), ctx.job.getId(),
                "MANIFEST", Json.write(manifest).getBytes(StandardCharsets.UTF_8),
                "json", "application/json");
        pipelineService.finish(node, 1.0, pipelineService.detailOf(
                "artifact_id", artifact.getId(), "chunk_count", chunkCount));
    }

    private static int intOf(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待重试时被中断", ex);
        }
    }
}
