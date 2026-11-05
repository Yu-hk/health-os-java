package com.kangheng.healthos.config;

import java.net.URI;
import java.net.URISyntaxException;

import com.kangheng.healthos.provider.ocr.DisabledOcrProvider;
import com.kangheng.healthos.provider.ocr.HttpOcrProvider;
import com.kangheng.healthos.spi.ocr.OcrProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * OCR 装配。
 *
 * <p>用 {@code provider=none|paddle|generic} 三态而不是"有 base-url 就启用"：
 * 显式声明意图才能区分"我没配 OCR"和"我配了但地址写错了"。
 * 前者应当明确拒绝扫描件，后者应当在启动时就报出来。
 *
 * <p><b>{@code provider} 是唯一决定走哪个接口的开关</b>，{@code protocol} 只是它的历史别名。
 * 早期两个配置项各自决定"引擎名"和"接口路径"，结果是：只把 {@code provider} 改成
 * {@code paddle}（{@code protocol} 仍是默认的 {@code generic}）时，日志打着
 * "provider=paddle"、实际却打 {@code POST /ocr}，全程没有一句告警。
 * 现在两者的关系被收紧成三种合法组合，其余一律启动失败：
 * <ul>
 *   <li>{@code protocol} 留空 → 跟随 {@code provider}（推荐写法）；</li>
 *   <li>{@code protocol} 与 {@code provider} 一致；</li>
 *   <li>其它 → 报错。让人看一眼配置，而不是猜。</li>
 * </ul>
 */
@Configuration
public class OcrConfig {

    private static final Logger log = LoggerFactory.getLogger(OcrConfig.class);

    @Bean
    public OcrProvider ocrProvider(HealthOsProperties properties) {
        HealthOsProperties.Ocr config = properties.getOcr();
        String provider = config.getProvider() == null ? "none" : config.getProvider().toLowerCase();
        if ("none".equals(provider) || !StringUtils.hasText(provider)) {
            log.warn("OCR 未启用（healthos.ocr.provider=none）：扫描件与图片将明确失败，不做静默降级");
            return new DisabledOcrProvider();
        }
        if (!"paddle".equals(provider) && !"generic".equals(provider)) {
            throw new IllegalStateException("不支持的 healthos.ocr.provider=" + provider + "（可选 none/paddle/generic）");
        }
        if (!StringUtils.hasText(config.getBaseUrl())) {
            // 配了 provider 却没给地址属于配置错误，不能当成"没配"模糊处理
            throw new IllegalStateException(
                    "healthos.ocr.provider=" + provider + " 但 healthos.ocr.base-url 为空，拒绝启动");
        }

        // protocol 留空即跟随 provider；显式写了就必须一致，否则一定是有人只改了一处
        String protocol = StringUtils.hasText(config.getProtocol())
                ? config.getProtocol().trim().toLowerCase() : provider;
        if (!protocol.equals(provider)) {
            throw new IllegalStateException("healthos.ocr.provider=" + provider
                    + " 与 protocol=" + protocol + " 矛盾：provider 决定接口（paddle → /predict/ocr_system，"
                    + "generic → /ocr），protocol 留空即跟随 provider。请删掉 protocol 或改成 " + provider);
        }
        config.setProtocol(protocol);
        config.setBaseUrl(normalizeBaseUrl(config.getBaseUrl()));

        log.info("OCR 已启用 provider={} baseUrl={} dpi={} minConfidence={}",
                provider, config.getBaseUrl(), config.getDpi(), config.getMinConfidence());
        return new HttpOcrProvider(config);
    }

    /**
     * base-url 只允许"协议 + 主机 + 端口"，接口路径由 provider 决定。
     *
     * <p>放行带路径的写法会拼成 {@code /predict/ocr_system/predict/ocr_system}，
     * 服务端回 404，而 404 在 {@code HttpOcrProvider} 里被归类为"不可重试"——
     * 表现为"文档一上传就失败"，且日志里看不出是地址写错了。
     */
    static String normalizeBaseUrl(String raw) {
        String base = raw.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        URI uri;
        try {
            uri = new URI(base);
        } catch (URISyntaxException ex) {
            throw new IllegalStateException(
                    "healthos.ocr.base-url 不是合法 URL（需要 http://host:port）: " + raw, ex);
        }
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new IllegalStateException("healthos.ocr.base-url 只能给到主机和端口（如 http://127.0.0.1:8868），"
                    + "不要带 " + path + " 这类路径——接口路径由 healthos.ocr.provider 决定");
        }
        // 只认 http/https：`127.0.0.1:8868` 这种写法会被 URI 解析成 scheme="127.0.0.1"，
        // 光判 null 会放过去，然后由 RestClient 在第一次识别时抛出来。
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalStateException("healthos.ocr.base-url 必须以 http:// 或 https:// 开头: " + raw);
        }
        return base;
    }
}
