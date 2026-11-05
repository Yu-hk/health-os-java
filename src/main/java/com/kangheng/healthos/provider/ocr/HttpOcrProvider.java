package com.kangheng.healthos.provider.ocr;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kangheng.healthos.config.HealthOsProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.AbstractResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import com.kangheng.healthos.model.ocr.OcrResult;
import com.kangheng.healthos.spi.ocr.OcrException;
import com.kangheng.healthos.spi.ocr.OcrProvider;

/**
 * 通过 HTTP 调用外部 OCR 服务。
 *
 * <p>两种协议共用同一套失败语义：
 * <ul>
 *   <li>5xx / 429 / 连接失败 → {@code retryable=true}，上游可以退避重试；</li>
 *   <li>4xx（图片格式不支持、key 无效）→ 不可重试，直接失败并回传服务端原因；</li>
 *   <li>响应里一行都没解析出来 → 视为"本页无文字"，由调用方决定是跳过该页还是判空。</li>
 * </ul>
 *
 * <p>刻意不在这里做重试：重试策略属于流水线（要和节点进度、超时预算一起考虑），
 * 客户端只负责把错误分类清楚。
 */
public class HttpOcrProvider implements OcrProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpOcrProvider.class);

    private final RestClient client;
    private final HealthOsProperties.Ocr config;
    private final String engine;

    public HttpOcrProvider(HealthOsProperties.Ocr config) {
        this.config = config;
        // 引擎只看 provider：provider=paddle 就走 /predict/ocr_system。
        // 早期这里读的是 protocol，导致 provider=paddle 但 protocol 保持默认 generic 时
        // 静默去打 POST /ocr —— 配了等于没配，且日志里看不出区别。
        this.engine = "paddle".equalsIgnoreCase(config.getProvider()) ? "paddle" : "generic";
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Math.min(5_000, config.getTimeout().toMillis()));
        factory.setReadTimeout((int) config.getTimeout().toMillis());
        this.client = RestClient.builder()
                .baseUrl(config.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public String name() {
        return engine;
    }

    @Override
    public boolean enabled() {
        return StringUtils.hasText(config.getBaseUrl());
    }

    @Override
    public boolean healthy() {
        if (!enabled()) {
            return false;
        }
        try {
            if ("paddle".equals(engine)) {
                // PaddleHub Serving 只暴露 /predict/{module}，**没有 /health**。
                // 探 /health 会拿到 404，把一台完全正常的机器报成 unavailable，
                // 连带 /health/dependencies 的整体 status 从 ready 掉到 degraded（发布脚本据此拒上线）。
                // 因此改用 GET 打识别路由：405/400 说明路由在、进程活着。
                client.get().uri("/predict/ocr_system").retrieve().toBodilessEntity();
                return true;
            }
            client.get().uri("/health").retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            // 路由存在但方法/参数不对 —— 这是 paddle 唯一可靠的"服务在线"信号
            boolean alive = "paddle".equals(engine) && (status == 400 || status == 405 || status == 422);
            if (!alive) {
                log.warn("OCR 探活返回 HTTP {}（base-url 可能不是 " + engine + " 服务的地址）: {}",
                        status, trim(ex.getResponseBodyAsString()));
            }
            return alive;
        } catch (Exception ex) {
            log.debug("OCR 服务健康检查失败: {}", ex.getMessage());
            return false;
        }
    }

    @Override
    public OcrResult recognize(byte[] image, String mediaType) {
        if (!enabled()) {
            throw new OcrException("未配置 healthos.ocr.base-url，无法调用 OCR 服务");
        }
        if (image == null || image.length == 0) {
            throw new OcrException("待识别图片为空");
        }
        return "paddle".equals(engine) ? callPaddle(image) : callGeneric(image, mediaType);
    }

    /** generic：multipart 上传图片，返回 {lines:[{text,confidence,box}]}。 */
    private OcrResult callGeneric(byte[] image, String mediaType) {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new InMemoryResource(image, filenameFor(mediaType)));
        form.add("language", config.getLanguage());

        String body = execute(() -> client.post()
                .uri("/ocr")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .headers(headers -> {
                    if (StringUtils.hasText(config.getApiKey())) {
                        headers.setBearerAuth(config.getApiKey());
                    }
                })
                .body(form)
                .retrieve()
                .body(String.class));
        return OcrResponseParser.parseGeneric(body, engine);
    }

    /** paddle：POST /predict/ocr_system，body {images:[base64]}。 */
    private OcrResult callPaddle(byte[] image) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("images", List.of(Base64.getEncoder().encodeToString(image)));
        String body = execute(() -> client.post()
                .uri("/predict/ocr_system")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (StringUtils.hasText(config.getApiKey())) {
                        headers.setBearerAuth(config.getApiKey());
                    }
                })
                .body(payload)
                .retrieve()
                .body(String.class));
        return OcrResponseParser.parsePaddle(body, engine);
    }

    private String execute(java.util.function.Supplier<String> call) {
        try {
            return call.get();
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            boolean retryable = status >= 500 || status == 429;
            throw new OcrException("OCR 服务返回 HTTP " + status + ": " + trim(ex.getResponseBodyAsString()),
                    retryable, ex);
        } catch (ResourceAccessException ex) {
            throw new OcrException("OCR 服务不可达（" + config.getBaseUrl() + "）: " + rootMessage(ex), true, ex);
        }
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + ": " + current.getMessage();
    }

    private static String trim(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = body.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "...";
    }

    private static String filenameFor(String mediaType) {
        if (mediaType != null && mediaType.contains("jpeg")) {
            return "page.jpg";
        }
        return "page.png";
    }

    /** multipart 需要带文件名的字节资源；直接 ByteArrayResource 会缺少 filename 被服务端拒绝。 */
    private static final class InMemoryResource extends AbstractResource {

        private final byte[] content;
        private final String filename;

        private InMemoryResource(byte[] content, String filename) {
            this.content = content;
            this.filename = filename;
        }

        @Override
        public String getDescription() {
            return "OCR page " + filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }

        @Override
        public long contentLength() {
            return content.length;
        }

        @Override
        public java.io.InputStream getInputStream() throws IOException {
            return new ByteArrayInputStream(content);
        }

        @Override
        public boolean equals(Object other) {
            return this == other;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    static Duration probeTimeout(HealthOsProperties.Ocr config) {
        return config.getTimeout();
    }
}
