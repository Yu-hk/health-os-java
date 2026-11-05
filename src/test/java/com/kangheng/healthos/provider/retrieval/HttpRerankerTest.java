package com.kangheng.healthos.provider.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.kangheng.healthos.spi.retrieval.RetrievalReranker;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpReranker} 的契约测试：对着 JDK 内置桩端点验证
 * 「探活可用 / 解析并按分数重排 / 端点未就绪时抛异常由上层降级」。
 */
class HttpRerankerTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger healthStatus = new AtomicInteger(200);
    private final AtomicInteger rerankStatus = new AtomicInteger(200);
    private String rerankBody = "{\"results\":[{\"index\":1,\"score\":0.9},{\"index\":0,\"score\":0.2}]}";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", ex -> {
            byte[] b = "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(healthStatus.get(), b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/rerank", ex -> {
            byte[] b = rerankBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(rerankStatus.get(), b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("探活 200 时 available()=true")
    void availableWhenHealthy() {
        HttpReranker reranker = new HttpReranker(baseUrl);
        assertThat(reranker.available()).isTrue();
    }

    @Test
    @DisplayName("探活 503 时 available()=false（应用侧据此退回融合序）")
    void unavailableWhenHealth503() {
        healthStatus.set(503);
        HttpReranker reranker = new HttpReranker(baseUrl);
        assertThat(reranker.available()).isFalse();
    }

    @Test
    @DisplayName("解析 results 并按分数降序重排")
    void parsesAndReorders() throws Exception {
        HttpReranker reranker = new HttpReranker(baseUrl);
        List<RetrievalReranker.Passage> passages = List.of(
                new RetrievalReranker.Passage("chk_0", "黄芪补气"),
                new RetrievalReranker.Passage("chk_1", "熟地黄补血"));
        List<RetrievalReranker.RankedPassage> ranked = reranker.rerank("补气", passages);

        assertThat(ranked).extracting(RetrievalReranker.RankedPassage::chunkId)
                .containsExactly("chk_1", "chk_0");
        assertThat(ranked.get(0).score()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("rerank 端点 503（模型未加载）时抛异常，由上层降级")
    void rerank503Throws() {
        rerankStatus.set(503);
        HttpReranker reranker = new HttpReranker(baseUrl);
        assertThatThrownBy(() -> reranker.rerank("补气",
                List.of(new RetrievalReranker.Passage("chk_0", "黄芪补气"))))
                .isInstanceOf(IllegalStateException.class);
    }
}
