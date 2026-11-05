package com.kangheng.healthos.provider.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.spi.ai.EmbeddingException;
import com.kangheng.healthos.spi.ai.EmbeddingModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Ollama 向量化实现（本地或自建实例）。
 *
 * <p>用 {@code /api/embed} 而不是老的 {@code /api/embeddings}：后者一次只收一条文本，
 * 索引一份两百片的典籍要发两百次请求；{@code /api/embed} 支持 {@code input} 数组，
 * 一次一条 batch。
 *
 * <p><b>为什么带 {@code truncate:true}</b>：bge-m3 的上下文是 8192，超长切片会直接报错。
 * 本地切片是 200 字，正常不会触发；但患者资料绑定那条路会传入外部引擎的整段文本，
 * 让它截断比让它 500 更合理——截断的是尾部，不是随机丢中间。
 */
public class OllamaEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingModel.class);

    private final HealthOsProperties.Embedding config;
    private final RestClient client;

    public OllamaEmbeddingModel(HealthOsProperties.Embedding config) {
        this.config = config;
        Duration timeout = config.getTimeout() == null ? Duration.ofSeconds(30) : config.getTimeout();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder()
                .baseUrl(trimTrailingSlash(config.getBaseUrl()))
                .requestFactory(factory)
                .build();
    }

    @Override
    public String id() {
        return "ollama:" + config.getModel();
    }

    @Override
    public int dimension() {
        return config.getDimension();
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[0];
        }
        List<float[]> vectors = embedBatch(List.of(text));
        return vectors.isEmpty() ? new float[0] : vectors.get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        int batchSize = Math.max(1, config.getBatchSize());
        List<float[]> result = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            int to = Math.min(texts.size(), from + batchSize);
            result.addAll(request(texts.subList(from, to)));
        }
        return result;
    }

    private List<float[]> request(List<String> batch) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        body.put("input", batch);
        body.put("truncate", true);
        if (config.getKeepAlive() != null && !config.getKeepAlive().isBlank()) {
            body.put("keep_alive", config.getKeepAlive());
        }
        String raw;
        try {
            raw = client.post()
                    .uri("/api/embed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException ex) {
            // 4xx 多半是模型名写错 / 维度不符，重试没有意义；5xx 才值得再试
            boolean retryable = ex.getStatusCode().is5xxServerError();
            throw new EmbeddingException("EMBEDDING_HTTP_" + ex.getStatusCode().value(),
                    "Ollama embedding 返回 " + ex.getStatusCode().value() + "：" + brief(ex.getResponseBodyAsString()),
                    retryable, ex);
        } catch (ResourceAccessException ex) {
            throw new EmbeddingException("EMBEDDING_UNAVAILABLE",
                    "连不上 Ollama（" + config.getBaseUrl() + "）：" + ex.getMessage(), true, ex);
        }
        return parse(raw, batch.size());
    }

    private List<float[]> parse(String raw, int expected) {
        JsonNode root;
        try {
            root = Json.mapper().readTree(raw == null ? "" : raw);
        } catch (Exception ex) {
            throw new EmbeddingException("EMBEDDING_BAD_RESPONSE", "Ollama 返回的不是 JSON", false, ex);
        }
        JsonNode embeddings = root == null ? null : root.get("embeddings");
        if (embeddings == null || !embeddings.isArray() || embeddings.isEmpty()) {
            throw new EmbeddingException("EMBEDDING_EMPTY", "Ollama 没有返回 embeddings", false);
        }
        if (embeddings.size() != expected) {
            // 顺序绑定是这个接口唯一的安全前提：数量对不上就不该猜哪条对应哪条
            throw new EmbeddingException("EMBEDDING_COUNT_MISMATCH",
                    "Ollama 返回 " + embeddings.size() + " 条向量，期望 " + expected + " 条", false);
        }
        List<float[]> result = new ArrayList<>(expected);
        int expectedDim = config.getDimension();
        for (JsonNode node : embeddings) {
            if (!node.isArray() || node.isEmpty()) {
                throw new EmbeddingException("EMBEDDING_EMPTY", "存在空向量", false);
            }
            float[] vector = new float[node.size()];
            for (int i = 0; i < node.size(); i++) {
                double value = node.get(i).asDouble();
                if (Double.isNaN(value) || Double.isInfinite(value)) {
                    throw new EmbeddingException("EMBEDDING_INVALID", "向量含 NaN/Infinity", false);
                }
                vector[i] = (float) value;
            }
            if (expectedDim > 0 && vector.length != expectedDim) {
                throw new EmbeddingException("EMBEDDING_DIM_MISMATCH",
                        "模型 " + config.getModel() + " 输出 " + vector.length + " 维，配置为 " + expectedDim
                                + " 维——改模型请同步改 healthos.embedding.dimension", false);
            }
            result.add(EmbeddingModel.normalize(vector));
        }
        return result;
    }

    @Override
    public boolean healthy() {
        try {
            String raw = client.get().uri("/api/tags").retrieve().body(String.class);
            JsonNode models = Json.mapper().readTree(raw == null ? "" : raw).get("models");
            if (models == null || !models.isArray()) {
                return false;
            }
            String wanted = config.getModel() == null ? "" : config.getModel();
            for (JsonNode model : models) {
                String name = model.path("name").asText("");
                if (name.equals(wanted) || name.equals(wanted + ":latest")
                        || stripTag(name).equals(stripTag(wanted))) {
                    return true;
                }
            }
            log.warn("Ollama 在跑，但没有模型 {}（已装：{}）", wanted, modelNames(models));
            return false;
        } catch (Exception ex) {
            log.debug("Ollama 探活失败：{}", ex.getMessage());
            return false;
        }
    }

    private static String modelNames(JsonNode models) {
        List<String> names = new ArrayList<>();
        models.forEach(node -> names.add(node.path("name").asText("")));
        return String.join(",", names);
    }

    private static String stripTag(String name) {
        if (name == null) {
            return "";
        }
        int colon = name.lastIndexOf(':');
        return colon > 0 ? name.substring(0, colon) : name;
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String brief(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "…";
    }
}
