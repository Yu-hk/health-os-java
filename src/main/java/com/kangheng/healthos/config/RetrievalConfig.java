package com.kangheng.healthos.config;

import com.kangheng.healthos.provider.retrieval.ChunkIndexService;
import com.kangheng.healthos.provider.retrieval.ChunkVectorService;
import com.kangheng.healthos.provider.retrieval.HybridKnowledgeBase;
import com.kangheng.healthos.spi.retrieval.KnowledgeBase;
import com.kangheng.healthos.provider.retrieval.LocalKnowledgeBase;
import com.kangheng.healthos.provider.retrieval.RagflowKnowledgeBase;
import com.kangheng.healthos.mapper.retrieval.KnowledgeIndexMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 知识库装配。
 *
 * <p>三种模式在启动期就确定，不做运行期热切换：
 * 索引口径变了（本地切片 vs RAGFlow 切片）必须重建，热切换会让"库里有索引、
 * 检索走另一套"的错配静默发生。
 */
@Configuration
public class RetrievalConfig {

    private static final Logger log = LoggerFactory.getLogger(RetrievalConfig.class);

    @Bean
    public KnowledgeBase knowledgeBase(HealthOsProperties properties,
                                       ChunkIndexService chunkIndexService,
                                       ChunkVectorService chunkVectorService,
                                       KnowledgeIndexMapper indexMapper) {
        HealthOsProperties.Retrieval config = properties.getRetrieval();
        String provider = config.getProvider() == null ? "local" : config.getProvider().toLowerCase();
        LocalKnowledgeBase local = new LocalKnowledgeBase(chunkIndexService, chunkVectorService, properties);

        if ("local".equals(provider)) {
            if (chunkVectorService.enabled()) {
                log.info("知识库模式 local：切片落 document_chunk，检索走词面+向量融合"
                                + "（{}，weight={}，minScore={}）",
                        chunkVectorService.modelId(), config.getVectorWeight(), config.getVectorMinScore());
            } else {
                log.info("知识库模式 local：切片落 document_chunk，关键词打分检索（向量化未启用）");
            }
            return local;
        }
        if (!StringUtils.hasText(config.getRagflow().getBaseUrl())
                || !StringUtils.hasText(config.getRagflow().getApiKey())) {
            throw new IllegalStateException(
                    "healthos.retrieval.provider=" + provider + " 但 RAGFlow base-url 或 api-key 未配置，拒绝启动");
        }
        RagflowKnowledgeBase ragflow = new RagflowKnowledgeBase(config.getRagflow(), indexMapper);
        if ("ragflow".equals(provider)) {
            log.info("知识库模式 ragflow：baseUrl={} datasetPrefix={} parser={}",
                    config.getRagflow().getBaseUrl(), config.getRagflow().getDatasetPrefix(),
                    config.getRagflow().getParserId());
            return ragflow;
        }
        if ("hybrid".equals(provider)) {
            log.info("知识库模式 hybrid：双写本地与 RAGFlow，检索优先 RAGFlow 并降级");
            return new HybridKnowledgeBase(local, ragflow, chunkIndexService);
        }
        throw new IllegalStateException("不支持的 healthos.retrieval.provider=" + provider
                + "（可选 local/ragflow/hybrid）");
    }
}
