package com.kangheng.healthos.provider.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.spi.routing.ModelQuotaExceededException;
import com.kangheng.healthos.spi.routing.ModelRouteResolver;
import com.kangheng.healthos.spi.routing.ModelUsageMeter;
import com.kangheng.healthos.spi.routing.ModelUsageMeter.TokenUsage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 真实 HTTP 调用 + 计量挂钩。
 *
 * <p>桩的是上游，不是计量：这里要证明的是"用量从上游响应里读出来后，原样交给了计量端口"，
 * 以及"闸门在发请求<b>之前</b>就已经生效"——后者一旦顺序写反，超配额时钱照样花出去。
 */
class OpenAiCompatibleLlmProviderTest {

    /** 兼容入口（不带主线）的 pipelineKey 与 providerId，见 provider 内的 legacy()。 */
    private static final String SCOPE = "LEGACY";
    private static final String PROVIDER_ID = "deepseek";
    private static final String MODEL = "deepseek-v4-flash";

    private HttpServer stub;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private final AtomicReference<Integer> responseStatus = new AtomicReference<>(200);

    private ModelUsageMeter meter;
    private ModelRouteResolver resolver;
    private OpenAiCompatibleLlmProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/v1/chat/completions", this::handle);
        stub.start();

        meter = mock(ModelUsageMeter.class);
        resolver = mock(ModelRouteResolver.class);
        HealthOsProperties properties = new HealthOsProperties();
        // 走兼容入口：密钥与地址来自 healthos.ai.*，模型名与断言里的常量保持一致
        properties.getAi().setApiKey("test-secret");
        properties.getAi().setModel(MODEL);
        properties.getAi().setBaseUrl("http://127.0.0.1:" + stub.getAddress().getPort());
        properties.getAi().setTimeout(java.time.Duration.ofSeconds(10));
        // 兼容入口的 providerId 取 baseline-provider-id，默认是 qwen；这里显式改成 deepseek
        properties.getModelRouting().setBaselineProviderId("deepseek");
        provider = new OpenAiCompatibleLlmProvider(properties, resolver, meter);
    }

    @AfterEach
    void tearDown() {
        if (stub != null) {
            stub.stop(0);
            stub = null;
        }
    }

    @Test
    @DisplayName("成功调用：把上游 usage 原样记一笔，金额换算不在 provider 里做")
    void recordsUsageReportedByUpstream() {
        responseBody.set("""
                {"choices":[{"message":{"content":"答案"}}],
                 "usage":{"prompt_tokens":120,"completion_tokens":30,"total_tokens":150}}
                """);

        String answer = provider.chat("系统提示", "问题");

        assertThat(answer).isEqualTo("答案");
        verify(meter).assertWithinQuota(eq(SCOPE), eq(MODEL));
        verify(meter).record(eq(SCOPE), eq(PROVIDER_ID), eq(MODEL),
                org.mockito.ArgumentMatchers.isNull(), eq(TokenUsage.of(120, 30, 150)));
    }

    @Test
    @DisplayName("上游没回报 usage 时不记账（零行只会让调用次数虚高）")
    void skipsRecordingWithoutUsage() {
        responseBody.set("{\"choices\":[{\"message\":{\"content\":\"答案\"}}]}");

        provider.chat("系统提示", "问题");

        verify(meter, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("配额已超时在发请求之前就失败：上游一次都没被调用")
    void quotaGateBlocksBeforeRequest() {
        org.mockito.Mockito.doThrow(new ModelQuotaExceededException(
                        ModelQuotaExceededException.QUOTA_TOKENS, "超了", 1000, 1000))
                .when(meter).assertWithinQuota(anyString(), anyString());

        assertThatThrownBy(() -> provider.chat("系统提示", "问题"))
                .isInstanceOf(ModelQuotaExceededException.class);
        assertThat(requests.get()).isZero();
    }

    @Test
    @DisplayName("上游 4xx 是不可重试错误，且不记用量（没成功就没花费）")
    void failedCallDoesNotRecordUsage() {
        responseStatus.set(401);
        responseBody.set("{\"error\":{\"message\":\"invalid api key\"}}");

        assertThatThrownBy(() -> provider.chat("系统提示", "问题"))
                .hasMessageContaining("401");
        verify(meter, never()).record(any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------ 夹具

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus.get(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
