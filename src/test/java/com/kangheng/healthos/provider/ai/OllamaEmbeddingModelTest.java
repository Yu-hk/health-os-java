package com.kangheng.healthos.provider.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.spi.ai.EmbeddingException;

/**
 * Ollama 客户端的契约测试：用本地 HTTP 桩，不依赖真 Ollama。
 *
 * <p>钉住四件事：向量必须归一化、批量顺序不能乱、数量/维度不符要报错、
 * 4xx 与 5xx 的可重试语义要分开（前者重试无用，后者值得再试）。
 */
class OllamaEmbeddingModelTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private HealthOsProperties.Embedding config(int port, int dimension) {
        HealthOsProperties.Embedding config = new HealthOsProperties.Embedding();
        config.setBaseUrl("http://127.0.0.1:" + port);
        config.setModel("bge-m3");
        config.setDimension(dimension);
        config.setBatchSize(2);
        config.setTimeout(java.time.Duration.ofSeconds(5));
        return config;
    }

    /** 起一个只回固定 JSON 的桩；requests 记录请求次数，便于断言分片。 */
    private int startStub(AtomicReference<String> embedBody, AtomicReference<Integer> embedStatus,
                          AtomicReference<String> tagsBody, AtomicInteger requests) throws IOException {
        return startStub(embedBody, embedStatus, tagsBody, requests, false);
    }

    /**
     * @param echoInput true 时按请求里的 input 条数生成等量向量（用于验证分片与顺序），
     *                  否则原样返回 {@code embedBody}
     */
    private int startStub(AtomicReference<String> embedBody, AtomicReference<Integer> embedStatus,
                          AtomicReference<String> tagsBody, AtomicInteger requests,
                          boolean echoInput) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> {
            requests.incrementAndGet();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response = echoInput ? vectorsFor(request) : embedBody.get();
            reply(exchange, embedStatus.get(), response);
        });
        server.createContext("/api/tags", exchange -> {
            drain(exchange);
            reply(exchange, 200, tagsBody.get());
        });
        server.start();
        return server.getAddress().getPort();
    }

    /**
     * 按入参文本生成单位向量：{@code "a" → [1,0,0]}、{@code "b" → [0,1,0]}、{@code "c" → [0,0,1]}。
     *
     * <p>刻意按**文本**而不是按批次内下标生成：分片之后每批的下标都从 0 开始，
     * 按下标生成会让"第二片被当成第一片"这种顺序错误看起来是对的。
     */
    private static String vectorsFor(String request) throws IOException {
        com.fasterxml.jackson.databind.JsonNode root =
                com.kangheng.healthos.common.Json.mapper().readTree(request);
        com.fasterxml.jackson.databind.JsonNode input = root.path("input");
        StringBuilder body = new StringBuilder("{\"embeddings\":[");
        for (int i = 0; i < input.size(); i++) {
            if (i > 0) {
                body.append(',');
            }
            String text = input.get(i).asText("");
            int axis = text.isEmpty() ? 0 : (text.charAt(0) - 'a') % 3;
            body.append('[');
            for (int j = 0; j < 3; j++) {
                if (j > 0) {
                    body.append(',');
                }
                body.append(j == axis ? '1' : '0');
            }
            body.append(']');
        }
        return body.append("]}").toString();
    }

    private static void drain(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    @DisplayName("单条向量化：结果已 L2 归一化，维度与配置一致")
    void embedNormalizes() throws IOException {
        AtomicReference<String> body = new AtomicReference<>(
                "{\"model\":\"bge-m3\",\"embeddings\":[[3,4,0]]}");
        int port = startStub(body, new AtomicReference<>(200),
                new AtomicReference<>("{\"models\":[]}"), new AtomicInteger());

        OllamaEmbeddingModel model = new OllamaEmbeddingModel(config(port, 3));
        float[] vector = model.embed("四君子汤");

        assertThat(vector).hasSize(3);
        assertThat(vector[0]).isCloseTo(0.6f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(vector[1]).isCloseTo(0.8f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(EmbeddingModelHelper.norm(vector)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(model.id()).isEqualTo("ollama:bge-m3");
    }

    @Test
    @DisplayName("批量按 batch-size 分片，且返回顺序与入参一致")
    void batchKeepsOrderAndSplits() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        int port = startStub(new AtomicReference<>(""), new AtomicReference<>(200),
                new AtomicReference<>("{\"models\":[]}"), requests, true);

        OllamaEmbeddingModel model = new OllamaEmbeddingModel(config(port, 3));
        List<float[]> vectors = model.embedBatch(List.of("a", "b", "c"));

        assertThat(vectors).hasSize(3);
        assertThat(vectors.get(0)[0]).isEqualTo(1f);
        assertThat(vectors.get(1)[1]).isEqualTo(1f);
        assertThat(vectors.get(2)[2]).isEqualTo(1f);
        // batch-size=2 → 3 条分成 2 次请求
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("数量不符直接报错：顺序绑定是这个接口唯一的安全前提")
    void countMismatchFails() throws IOException {
        AtomicReference<String> body = new AtomicReference<>("{\"embeddings\":[[1,0,0]]}");
        int port = startStub(body, new AtomicReference<>(200),
                new AtomicReference<>("{\"models\":[]}"), new AtomicInteger());

        OllamaEmbeddingModel model = new OllamaEmbeddingModel(config(port, 3));

        assertThatThrownBy(() -> model.embedBatch(List.of("a", "b")))
                .isInstanceOf(EmbeddingException.class)
                .hasFieldOrPropertyWithValue("code", "EMBEDDING_COUNT_MISMATCH");
    }

    @Test
    @DisplayName("维度不符明确提示改配置，不静默入库")
    void dimensionMismatchFails() throws IOException {
        AtomicReference<String> body = new AtomicReference<>("{\"embeddings\":[[1,0]]}");
        int port = startStub(body, new AtomicReference<>(200),
                new AtomicReference<>("{\"models\":[]}"), new AtomicInteger());

        OllamaEmbeddingModel model = new OllamaEmbeddingModel(config(port, 3));

        assertThatThrownBy(() -> model.embed("四君子汤"))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("healthos.embedding.dimension");
    }

    @Test
    @DisplayName("5xx 可重试、4xx 不可重试：降级策略据此决定要不要冷却")
    void retryableSemantics() throws IOException {
        AtomicReference<Integer> status = new AtomicReference<>(500);
        int port = startStub(new AtomicReference<>("{\"error\":\"boom\"}"), status,
                new AtomicReference<>("{\"models\":[]}"), new AtomicInteger());
        OllamaEmbeddingModel model = new OllamaEmbeddingModel(config(port, 3));

        assertThatThrownBy(() -> model.embed("x"))
                .isInstanceOf(EmbeddingException.class)
                .hasFieldOrPropertyWithValue("retryable", true);

        status.set(404);
        assertThatThrownBy(() -> model.embed("x"))
                .isInstanceOf(EmbeddingException.class)
                .hasFieldOrPropertyWithValue("retryable", false)
                .hasFieldOrPropertyWithValue("code", "EMBEDDING_HTTP_404");
    }

    @Test
    @DisplayName("探活看 /api/tags 里有没有这个模型，不是只看进程在不在")
    void healthyChecksModelPresence() throws IOException {
        AtomicReference<String> tags = new AtomicReference<>(
                "{\"models\":[{\"name\":\"bge-m3:latest\"}]}");
        int port = startStub(new AtomicReference<>("{\"embeddings\":[]}"), new AtomicReference<>(200),
                tags, new AtomicInteger());
        OllamaEmbeddingModel model = new OllamaEmbeddingModel(config(port, 3));
        assertThat(model.healthy()).isTrue();

        tags.set("{\"models\":[{\"name\":\"llama3:latest\"}]}");
        OllamaEmbeddingModel other = new OllamaEmbeddingModel(config(port, 3));
        assertThat(other.healthy()).isFalse();
    }

    @Test
    @DisplayName("空白文本不发请求，返回空向量")
    void blankTextSkipsRequest() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        int port = startStub(new AtomicReference<>("{\"embeddings\":[]}"), new AtomicReference<>(200),
                new AtomicReference<>("{\"models\":[]}"), requests);
        OllamaEmbeddingModel model = new OllamaEmbeddingModel(config(port, 3));

        assertThat(model.embed("   ")).isEmpty();
        assertThat(requests.get()).isZero();
    }

    private static final class EmbeddingModelHelper {
        private EmbeddingModelHelper() {
        }

        static double norm(float[] vector) {
            double sum = 0;
            for (float value : vector) {
                sum += (double) value * value;
            }
            return Math.sqrt(sum);
        }
    }
}
