package com.kangheng.healthos.config;

import com.kangheng.healthos.mapper.graph.EvidenceMapper;
import com.kangheng.healthos.mapper.graph.GraphEdgeMapper;
import com.kangheng.healthos.mapper.graph.GraphNodeMapper;
import com.kangheng.healthos.provider.graph.DualGraphStore;
import com.kangheng.healthos.spi.graph.GraphStore;
import com.kangheng.healthos.provider.graph.JdbcGraphStore;
import com.kangheng.healthos.provider.graph.Neo4jGraphStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.StringUtils;

/**
 * 图存储装配。
 *
 * <p>Neo4j 的 driver 只有在 {@code store=neo4j|dual} 时才会被创建。
 * 这一点很重要：默认的 local 部署不该因为"装了个没配密码的 Neo4j 客户端"而启动失败。
 */
@Slf4j
@Configuration
public class GraphConfig {

    @Bean
    public GraphStore graphStore(HealthOsProperties properties,
                                 GraphNodeMapper nodeMapper,
                                 GraphEdgeMapper edgeMapper,
                                 EvidenceMapper evidenceMapper,
                                 PlatformTransactionManager transactionManager) {
        HealthOsProperties.Graph config = properties.getGraph();
        String store = config.getStore() == null ? "jdbc" : config.getStore().toLowerCase();
        JdbcGraphStore jdbc = new JdbcGraphStore(nodeMapper, edgeMapper, evidenceMapper, transactionManager);

        if ("jdbc".equals(store)) {
            log.info("图存储模式 jdbc：节点/边/证据落 PostgreSQL");
            return jdbc;
        }
        if (!"neo4j".equals(store) && !"dual".equals(store)) {
            throw new IllegalStateException("不支持的 healthos.graph.store=" + store + "（可选 jdbc/neo4j/dual）");
        }
        if (!StringUtils.hasText(config.getNeo4j().getPassword())) {
            throw new IllegalStateException(
                    "healthos.graph.store=" + store + " 但 healthos.graph.neo4j.password 为空，拒绝启动");
        }
        Neo4jGraphStore neo4j = new Neo4jGraphStore(config.getNeo4j(), evidenceMapper, transactionManager);
        log.info("图存储模式 {}：Neo4j uri={} database={} maxDepth={}",
                store, config.getNeo4j().getUri(), config.getNeo4j().getDatabase(),
                config.getNeo4j().getMaxDepth());
        if ("neo4j".equals(store)) {
            return neo4j;
        }
        return new DualGraphStore(jdbc, neo4j);
    }
}
