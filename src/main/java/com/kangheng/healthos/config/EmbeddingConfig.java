package com.kangheng.healthos.config;

import com.kangheng.healthos.provider.ai.NoopEmbeddingModel;
import com.kangheng.healthos.provider.ai.OllamaEmbeddingModel;
import com.kangheng.healthos.spi.ai.EmbeddingModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 向量化装配。
 *
 * <p>与知识库一样是**启动期定型**：换模型等于换一套向量（维度、语义空间都变了），
 * 运行期热切换会出现"一半切片是旧模型的向量、一半是新模型的"，检索分数全是噪声。
 *
 * <p>{@code none} 是合法配置，装配 {@link NoopEmbeddingModel} 而不是不装配——后者会让
 * 所有调用点都长出一个判空分支。这里刻意**不做启动期强校验**（连不上 Ollama 也允许启动）：
 * 向量化是可选增强，挂掉时问答应当降级到词面检索，而不是整个服务起不来。
 */
@Configuration
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    @Bean
    public EmbeddingModel embeddingModel(HealthOsProperties properties) {
        HealthOsProperties.Embedding config = properties.getEmbedding();
        String provider = config.getProvider() == null ? "none" : config.getProvider().trim().toLowerCase();
        if ("none".equals(provider) || !StringUtils.hasText(provider)) {
            log.info("向量化未启用：检索走词面打分（healthos.embedding.provider=none）");
            return new NoopEmbeddingModel();
        }
        if ("ollama".equals(provider)) {
            if (!StringUtils.hasText(config.getBaseUrl()) || !StringUtils.hasText(config.getModel())) {
                throw new IllegalStateException(
                        "healthos.embedding.provider=ollama 但 base-url 或 model 未配置，拒绝启动");
            }
            log.info("向量化启用：ollama baseUrl={} model={} dim={} batch={}",
                    config.getBaseUrl(), config.getModel(), config.getDimension(), config.getBatchSize());
            return new OllamaEmbeddingModel(config);
        }
        throw new IllegalStateException("不支持的 healthos.embedding.provider=" + provider + "（可选 ollama/none）");
    }
}
