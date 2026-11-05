package com.kangheng.healthos.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.spi.ocr.OcrProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OCR 装配的三条边界：provider/protocol 不能各说各话、base-url 不能带路径、留空要能跟随。
 *
 * <p>这三条都是"接真服务时才暴露"的配置陷阱，靠看代码发现不了：
 * 早期 provider=paddle + protocol=generic（默认值）会静默打 {@code POST /ocr}，
 * 日志里照样打着 provider=paddle。
 */
class OcrConfigTest {

    private static HealthOsProperties.Ocr config(String provider, String protocol, String baseUrl) {
        HealthOsProperties.Ocr config = new HealthOsProperties.Ocr();
        config.setProvider(provider);
        config.setProtocol(protocol);
        config.setBaseUrl(baseUrl);
        return config;
    }

    private static OcrProvider build(String provider, String protocol, String baseUrl) {
        HealthOsProperties properties = new HealthOsProperties();
        properties.setOcr(config(provider, protocol, baseUrl));
        return new OcrConfig().ocrProvider(properties);
    }

    @Test
    @DisplayName("protocol 留空时跟随 provider：paddle 不会被当成 generic")
    void blankProtocolFollowsProvider() {
        OcrProvider provider = build("paddle", "", "http://127.0.0.1:8868");

        assertThat(provider.name()).isEqualTo("paddle");
        assertThat(provider.enabled()).isTrue();
    }

    @Test
    @DisplayName("protocol 与 provider 矛盾时启动失败，而不是静默走另一条接口")
    void contradictingProtocolIsRejected() {
        assertThatThrownBy(() -> build("paddle", "generic", "http://127.0.0.1:8868"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("矛盾")
                .hasMessageContaining("provider=paddle");
    }

    @Test
    @DisplayName("base-url 带接口路径时启动失败：会拼成重复路径并得到不可重试的 404")
    void baseUrlWithPathIsRejected() {
        assertThatThrownBy(() -> build("paddle", "", "http://127.0.0.1:8868/predict/ocr_system"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只能给到主机和端口");
    }

    @Test
    @DisplayName("base-url 末尾多余的斜杠被去掉，避免拼出 //predict")
    void trailingSlashIsNormalized() {
        assertThat(OcrConfig.normalizeBaseUrl("http://127.0.0.1:8868///"))
                .isEqualTo("http://127.0.0.1:8868");
        assertThat(OcrConfig.normalizeBaseUrl("  http://host:8868  "))
                .isEqualTo("http://host:8868");
    }

    @Test
    @DisplayName("缺 http:// 前缀时报错，不要等到第一次识别才炸")
    void schemeIsRequired() {
        assertThatThrownBy(() -> OcrConfig.normalizeBaseUrl("127.0.0.1:8868"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("http");
    }

    @Test
    @DisplayName("provider=none 时不校验 base-url，直接给禁用实现")
    void noneSkipsValidation() {
        OcrProvider provider = build("none", "", "");

        assertThat(provider.enabled()).isFalse();
    }
}
