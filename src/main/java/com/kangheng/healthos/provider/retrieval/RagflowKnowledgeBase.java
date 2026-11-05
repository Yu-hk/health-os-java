package com.kangheng.healthos.provider.retrieval;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.kangheng.healthos.common.ByteArrayPart;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.mapper.retrieval.KnowledgeIndexMapper;
import com.kangheng.healthos.model.retrieval.KnowledgeIndexEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import com.kangheng.healthos.spi.retrieval.KnowledgeBase;
import com.kangheng.healthos.spi.retrieval.KnowledgeBaseException;

/**
 * RAGFlow 知识库实现，对齐官方 HTTP API v1。
 *
 * <p>调用序列：<b>重建数据集 → 上传 Markdown → 触发解析 → 轮询至 DONE → 登记 datasetId</b>。
 * 几个来自真实契约的细节：
 * <ul>
 *   <li>1.0 的创建数据集用 {@code parse_type=1} + {@code parser_id}，旧字段 {@code chunk_method} 已不被接受；</li>
 *   <li>成功与否看响应体 {@code code==0}，<b>不能只看 HTTP 状态码</b>——RAGFlow 会用 200 返回业务错误；</li>
 *   <li>检索端点用 {@code page_size}，{@code top_k} 已废弃。</li>
 * </ul>
 *
 * <p>为什么"重建而非增量"：典籍是整篇变化的（OCR 重跑会改变全部偏移），
 * 增量 update 反而容易留下与新文本对不上的旧切片。整篇替换在语义上更安全。
 */
public class RagflowKnowledgeBase implements KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(RagflowKnowledgeBase.class);

    private static final String ENGINE = "ragflow";
    private static final String DATASET_LANGUAGE = "Chinese";
    private static final int ERROR_BODY_LIMIT = 300;

    private final RestClient client;
    private final HealthOsProperties.Ragflow config;
    private final KnowledgeIndexMapper indexMapper;

    public RagflowKnowledgeBase(HealthOsProperties.Ragflow config, KnowledgeIndexMapper indexMapper) {
        this.config = config;
        this.indexMapper = indexMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout((int) config.getTimeout().toMillis());
        this.client = RestClient.builder()
                .baseUrl(trimTrailingSlash(config.getBaseUrl()))
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + config.getApiKey())
                .build();
    }

    @Override
    public String name() {
        return ENGINE;
    }

    @Override
    public boolean healthy() {
        try {
            call(HttpMethod.GET, "/api/v1/datasets?page=1&page_size=1", null, null);
            return true;
        } catch (Exception ex) {
            log.debug("RAGFlow 健康检查失败: {}", ex.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------- 索引

    @Override
    public IndexOutcome index(String documentId, String title, String markdown) {
        long started = System.nanoTime();
        // 先清掉上一轮的索引：整篇替换，避免旧切片残留
        delete(documentId);

        String datasetId = null;
        try {
            datasetId = createDataset(documentId, title);
            String externalDocId = uploadDocument(datasetId, title, markdown);
            triggerParse(datasetId, externalDocId);
            int chunks = awaitParse(datasetId, externalDocId);
            saveIndex(documentId, datasetId, externalDocId, chunks, "OK", null);
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            log.info("RAGFlow 索引完成 doc={} dataset={} chunks={} 耗时={}ms",
                    documentId, datasetId, chunks, elapsed);
            return new IndexOutcome(ENGINE, chunks, datasetId, elapsed);
        } catch (RuntimeException ex) {
            // 失败时清掉半成品数据集，否则反复重试会在 RAGFlow 里堆垃圾
            if (datasetId != null) {
                safeDeleteDataset(datasetId);
            }
            saveIndex(documentId, null, null, 0, "FAILED", ex.getMessage());
            throw ex;
        }
    }

    private String createDataset(String documentId, String title) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", datasetName(documentId));
        payload.put("parse_type", config.getParseType());
        payload.put("parser_id", config.getParserId());
        payload.put("language", DATASET_LANGUAGE);
        if (StringUtils.hasText(config.getEmbeddingModel())) {
            payload.put("embedding_model", config.getEmbeddingModel());
        }
        JsonNode data = call(HttpMethod.POST, "/api/v1/datasets", payload, null);
        String datasetId = data.path("id").asText("");
        if (!StringUtils.hasText(datasetId)) {
            throw new KnowledgeBaseException("RAGFlow 创建数据集未返回 id（" + safeTitle(title) + "）", false);
        }
        return datasetId;
    }

    private String uploadDocument(String datasetId, String title, String markdown) {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        form.add("file", new ByteArrayPart(bytes, markdownFilename(title)));
        JsonNode data = call(HttpMethod.POST, "/api/v1/datasets/" + datasetId + "/documents", null, form);
        JsonNode first = data.isArray() && !data.isEmpty() ? data.get(0) : data;
        String docId = first.path("id").asText("");
        if (!StringUtils.hasText(docId)) {
            throw new KnowledgeBaseException("RAGFlow 上传文档未返回 id", false);
        }
        return docId;
    }

    private void triggerParse(String datasetId, String externalDocId) {
        Map<String, Object> payload = Map.of("document_ids", List.of(externalDocId));
        call(HttpMethod.POST, "/api/v1/datasets/" + datasetId + "/chunks", payload, null);
    }

    /** 轮询解析状态直到 DONE / FAIL / 超时。 */
    private int awaitParse(String datasetId, String externalDocId) {
        long deadline = System.nanoTime() + config.getParseTimeout().toNanos();
        while (true) {
            JsonNode document = findDocument(datasetId, externalDocId);
            String run = document.path("run").asText("");
            if ("DONE".equalsIgnoreCase(run)) {
                return document.path("chunk_count").asInt(0);
            }
            if ("FAIL".equalsIgnoreCase(run) || "CANCEL".equalsIgnoreCase(run)) {
                throw new KnowledgeBaseException("RAGFlow 解析失败 run=" + run + "，原因: "
                        + document.path("progress_msg").asText(""), false);
            }
            if (System.nanoTime() > deadline) {
                throw new KnowledgeBaseException("RAGFlow 解析超时（" + config.getParseTimeout()
                        + "），当前 run=" + run + " progress=" + document.path("progress").asDouble(0), true);
            }
            sleep(config.getParsePollInterval().toMillis());
        }
    }

    private JsonNode findDocument(String datasetId, String externalDocId) {
        JsonNode data = call(HttpMethod.GET,
                "/api/v1/datasets/" + datasetId + "/documents?id=" + externalDocId, null, null);
        JsonNode docs = data.path("docs");
        if (!docs.isArray() || docs.isEmpty()) {
            throw new KnowledgeBaseException("RAGFlow 中查不到文档 " + externalDocId, false);
        }
        return docs.get(0);
    }

    // ---------------------------------------------------------------- 检索

    @Override
    public List<RetrievedChunk> retrieve(String documentId, String question, int topK) {
        KnowledgeIndexEntity record = indexMapper.selectById(documentId);
        if (record == null || !StringUtils.hasText(record.getDatasetId())) {
            throw new KnowledgeBaseException("文档 " + documentId + " 尚未在 RAGFlow 建立索引", false);
        }
        Map<String, Object> payload = retrievalPayload(question, topK);
        payload.put("dataset_ids", List.of(record.getDatasetId()));
        if (StringUtils.hasText(record.getExternalDocId())) {
            payload.put("document_ids", List.of(record.getExternalDocId()));
        }
        return search(payload);
    }

    /**
     * 跨库检索：不限定 document_ids。
     *
     * <p>患者绑定的库是多文档的，按文档过滤会把结果削成零——这里刻意不加过滤条件。
     */
    @Override
    public List<RetrievedChunk> retrieveDatasets(List<String> datasetIds, String question, int topK) {
        if (datasetIds == null || datasetIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> payload = retrievalPayload(question, topK);
        payload.put("dataset_ids", List.copyOf(datasetIds));
        return search(payload);
    }

    /**
     * 列出可用数据集。分页拉取，最多 5 页（500 个库），够用且不会把接口拖死。
     *
     * <p>真实 RAGFlow v1 的 {@code data} 是<b>数组</b>；个别版本与代理会包一层
     * {@code {"datasets": [...]}}。两种都认，但都是在"确定拿到了列表"的前提下，
     * 不会把空对象当成空库悄悄吞掉。
     */
    @Override
    public List<DatasetInfo> datasets() {
        List<DatasetInfo> result = new ArrayList<>();
        for (int page = 1; page <= 5; page++) {
            JsonNode data = call(HttpMethod.GET,
                    "/api/v1/datasets?page=" + page + "&page_size=100", null, null);
            JsonNode list = data.isArray() ? data : data.path("datasets");
            if (!list.isArray() || list.isEmpty()) {
                break;
            }
            for (JsonNode item : list) {
                String id = item.path("id").asText("");
                if (!StringUtils.hasText(id)) {
                    continue;
                }
                result.add(new DatasetInfo(id, item.path("name").asText(""),
                        item.path("chunk_count").asInt(0), item.path("document_count").asInt(0)));
            }
            if (list.size() < 100) {
                break;
            }
        }
        return result;
    }

    /**
     * 检索参数。
     *
     * <p>三个值与限流无关，是<b>口径</b>：对齐原工程 {@code qa_ragflow_preset} 的 Chat 检索配置
     * （similarity_threshold=0.2 / keywords_similarity_weight=0.3 / rerank_model 默认空）。
     * 原工程把向量检索与重排交给 RAGFlow，本地不算向量——这里保持同一分工。
     */
    private Map<String, Object> retrievalPayload(String question, int topK) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", question);
        payload.put("page", 1);
        payload.put("page_size", Math.max(1, topK));
        payload.put("similarity_threshold", config.getSimilarityThreshold());
        // 1 - weight 在浮点下会得到 0.7000000000000001，收一下尾免得日志里难看
        payload.put("vector_similarity_weight",
                Math.round((1.0 - config.getKeywordsSimilarityWeight()) * 10_000) / 10_000.0);
        payload.put("keyword", false);
        payload.put("highlight", false);
        if (StringUtils.hasText(config.getRerankId())) {
            payload.put("rerank_id", config.getRerankId());
        }
        return payload;
    }

    private List<RetrievedChunk> search(Map<String, Object> payload) {
        JsonNode data = call(HttpMethod.POST, "/api/v1/retrieval", payload, null);
        JsonNode chunks = data.path("chunks");
        if (!chunks.isArray()) {
            return List.of();
        }
        Map<String, String> localIds = mapLocalDocumentIds(chunks);
        List<RetrievedChunk> result = new ArrayList<>();
        for (JsonNode chunk : chunks) {
            String content = chunk.path("content").asText("");
            if (content.isBlank()) {
                continue;
            }
            String externalDocId = textOrNull(chunk, "document_id");
            result.add(new RetrievedChunk(chunk.path("id").asText(""),
                    externalDocId == null ? null : localIds.get(externalDocId), content,
                    null, null, null, chunk.path("similarity").asDouble(0)));
        }
        return result;
    }

    /**
     * 把 RAGFlow 的 {@code document_id} 换成本系统的典籍 ID。
     *
     * <p><b>为什么要换</b>：外部 ID 是 RAGFlow 自己生成的，前端拿它点不进
     * {@code /classics/{id}/chunks}——引用会变成"看得到文字、查不到出处"。
     * 映射依据是 {@code knowledge_index.external_doc_id}，索引时写下的对应关系，
     * 不是从命名字符串里猜出来的。
     *
     * <p>查不到就留 {@code null}：宁可承认"这条不知道出自哪份文档"，
     * 也不要拿一个点不开的 ID 冒充出处。
     */
    private Map<String, String> mapLocalDocumentIds(JsonNode chunks) {
        java.util.Set<String> externalIds = new java.util.LinkedHashSet<>();
        for (JsonNode chunk : chunks) {
            String external = textOrNull(chunk, "document_id");
            if (external != null) {
                externalIds.add(external);
            }
        }
        if (externalIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> mapping = new LinkedHashMap<>();
        try {
            List<KnowledgeIndexEntity> rows = indexMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeIndexEntity>()
                            .in(KnowledgeIndexEntity::getExternalDocId, externalIds));
            for (KnowledgeIndexEntity row : rows) {
                if (row.getExternalDocId() != null && row.getDocumentId() != null) {
                    mapping.putIfAbsent(row.getExternalDocId(), row.getDocumentId());
                }
            }
        } catch (RuntimeException ex) {
            // 映射失败只影响"引用可反查"，不该让整次检索失败
            log.warn("外部文档 ID 映射查询失败，本次引用不含本地文档归属: {}", ex.getMessage());
        }
        return mapping;
    }

    /** 取字符串字段；缺字段或空串一律返回 {@code null}，不把 "" 当成有效文档 ID 往下传。 */
    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText("");
        return text.isBlank() ? null : text;
    }

    // ---------------------------------------------------------------- 清理

    @Override
    public void delete(String documentId) {
        KnowledgeIndexEntity record = indexMapper.selectById(documentId);
        if (record != null && StringUtils.hasText(record.getDatasetId())) {
            safeDeleteDataset(record.getDatasetId());
        }
        indexMapper.deleteById(documentId);
    }

    private void safeDeleteDataset(String datasetId) {
        try {
            call(HttpMethod.DELETE, "/api/v1/datasets", Map.of("ids", List.of(datasetId)), null);
        } catch (Exception ex) {
            // 删除失败不该阻断主流程：数据集残留是脏数据，不是业务错误
            log.warn("清理 RAGFlow 数据集失败 dataset={}: {}", datasetId, ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- HTTP

    /**
     * 统一请求入口：校验 {@code code==0} 并把 {@code data} 解出来。
     *
     * <p>刻意不用 {@code retrieve().body(Class)} 直接反序列化到 Map：
     * 需要先看到 {@code code}，否则业务错误会被当成"空数据"静默吞掉。
     */
    private JsonNode call(HttpMethod method, String path, Object jsonBody,
                          LinkedMultiValueMap<String, Object> form) {
        String raw;
        try {
            RestClient.RequestBodySpec spec = client.method(method).uri(path);
            RestClient.ResponseSpec response;
            if (form != null) {
                response = spec.contentType(MediaType.MULTIPART_FORM_DATA).body(form).retrieve();
            } else if (jsonBody != null) {
                response = spec.contentType(MediaType.APPLICATION_JSON).body(jsonBody).retrieve();
            } else {
                response = spec.retrieve();
            }
            raw = response.body(String.class);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            throw new KnowledgeBaseException("RAGFlow 返回 HTTP " + status + ": "
                    + trim(ex.getResponseBodyAsString()), status >= 500 || status == 429, ex);
        } catch (ResourceAccessException ex) {
            throw new KnowledgeBaseException("RAGFlow 不可达（" + config.getBaseUrl() + "）: " + ex.getMessage(),
                    true, ex);
        }
        return unwrap(raw);
    }

    /** 拆信封；{@code code != 0} 一律视为失败，不把错误当空结果。 */
    private JsonNode unwrap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Json.mapper().createObjectNode();
        }
        JsonNode root;
        try {
            root = Json.mapper().readTree(raw);
        } catch (Exception ex) {
            throw new KnowledgeBaseException("RAGFlow 响应不是合法 JSON: " + trim(raw), true, ex);
        }
        if (root.has("code") && root.path("code").asInt(0) != 0) {
            int code = root.path("code").asInt();
            String message = root.path("message").asText("");
            // 109 鉴权失败、101/102 参数非法：重试无意义
            boolean retryable = code >= 500 || code == 100;
            throw new KnowledgeBaseException("RAGFlow 业务错误 code=" + code + " message=" + message, retryable);
        }
        JsonNode data = root.path("data");
        return data.isMissingNode() || data.isNull() ? Json.mapper().createObjectNode() : data;
    }

    // ---------------------------------------------------------------- 辅助

    private void saveIndex(String documentId, String datasetId, String externalDocId,
                           int chunkCount, String status, String detail) {
        KnowledgeIndexEntity entity = new KnowledgeIndexEntity();
        entity.setDocumentId(documentId);
        entity.setProvider(ENGINE);
        entity.setDatasetId(datasetId);
        entity.setExternalDocId(externalDocId);
        entity.setChunkCount(chunkCount);
        entity.setStatus(status);
        entity.setDetail(detail == null ? null : trim(detail));
        entity.setUpdatedAt(OffsetDateTime.now());
        if (indexMapper.selectById(documentId) == null) {
            indexMapper.insert(entity);
        } else {
            indexMapper.updateById(entity);
        }
    }

    private String datasetName(String documentId) {
        return config.getDatasetPrefix() + documentId;
    }

    /** RAGFlow 按后缀选择解析器，Markdown 必须带 .md；文件名同时作为文档标题。 */
    private String markdownFilename(String title) {
        String base = safeTitle(title).replaceAll("[\\\\/:*?\"<>|]", "_");
        return base + ".md";
    }

    private static String safeTitle(String title) {
        if (!StringUtils.hasText(title)) {
            return "classic";
        }
        String trimmed = title.strip();
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 80);
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String trim(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = body.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= ERROR_BODY_LIMIT ? oneLine
                : oneLine.substring(0, ERROR_BODY_LIMIT) + "...";
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new KnowledgeBaseException("等待 RAGFlow 解析时被中断", true, ex);
        }
    }
}
