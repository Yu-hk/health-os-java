package com.kangheng.healthos.provider.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kangheng.healthos.spi.retrieval.RetrievalReranker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * 通过 HTTP 调用外部 cross-encoder 重排服务的实现。
 *
 * <p>服务契约（见 {@code tools/rerank-service/server.py}）：
 * <pre>
 *   POST {baseUrl}/rerank
 *     req : {"query": "...", "passages": ["文本0", "文本1", ...]}
 *     resp: {"results": [{"index": 0, "score": 0.92}, ...]}  // index 对应入参 passages 下标
 *   GET  {baseUrl}/health  -> 200 表示模型已加载
 * </pre>
 *
 * <p><b>不可用时绝不拖慢主链路</b>：连接/读超时都压得很短（连不上 1.5s 内判定），
 * {@link #available()} 返回 {@code false}；{@link #rerank} 任何异常都原样抛出，由
 * {@code LocalKnowledgeBase} 捕获后退回融合序。这是"增强不是依赖"的硬保证。
 */
public class HttpReranker implements RetrievalReranker {

    private static final Logger log = LoggerFactory.getLogger(HttpReranker.class);

    private final String baseUrl;
    private final RestTemplate rest;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpReranker(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1500);
        factory.setReadTimeout(8000);
        this.rest = new RestTemplate(factory);
    }

    @Override
    public boolean available() {
        try {
            var status = rest.getForEntity(baseUrl + "/health", String.class).getStatusCode();
            return status == HttpStatus.OK;
        } catch (RestClientException ex) {
            log.debug("重排服务不可用（探活失败）: {}", ex.getMessage());
            return false;
        }
    }

    @Override
    public List<RankedPassage> rerank(String query, List<Passage> passages) {
        if (passages.isEmpty()) {
            return List.of();
        }
        List<String> texts = new ArrayList<>(passages.size());
        for (Passage p : passages) {
            texts.add(p.text());
        }
        Map<String, Object> body = Map.of("query", query, "passages", texts);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> resp;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = rest.postForObject(
                    baseUrl + "/rerank",
                    new HttpEntity<>(body, headers),
                    Map.class);
            resp = raw;
        } catch (HttpStatusCodeException ex) {
            throw new IllegalStateException("重排服务返回 " + ex.getStatusCode() + ": " + ex.getStatusText(), ex);
        } catch (RestClientException ex) {
            throw new IllegalStateException("重排服务不可达: " + ex.getMessage(), ex);
        }
        if (resp == null || !resp.containsKey("results")) {
            throw new IllegalStateException("重排响应缺少 results 字段");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) resp.get("results");
        List<RankedPassage> ranked = new ArrayList<>(results.size());
        for (Map<String, Object> r : results) {
            int index = ((Number) r.get("index")).intValue();
            double score = ((Number) r.get("score")).doubleValue();
            if (index < 0 || index >= passages.size()) {
                throw new IllegalStateException("重排返回越界 index=" + index);
            }
            ranked.add(new RankedPassage(passages.get(index).chunkId(), score));
        }
        ranked.sort(Comparator.comparingDouble(RankedPassage::score).reversed());
        return ranked;
    }

    @Override
    public String name() {
        return baseUrl;
    }
}
