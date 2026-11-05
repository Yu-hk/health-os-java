package com.kangheng.healthos.service.graph;

import java.util.List;
import java.util.Map;

import com.kangheng.healthos.spi.graph.MergedGraph;
import com.kangheng.healthos.model.graph.GraphView;
import com.kangheng.healthos.model.graph.EvidenceEntity;
import com.kangheng.healthos.spi.graph.GraphStore;
import com.kangheng.healthos.service.tracing.TracingService;

import org.springframework.stereotype.Service;

/**
 * 图谱门面。
 *
 * <p>本身不实现任何存储细节，只把 {@link GraphStore} 暴露给流水线与接口层。
 * 这样换图库（PG ↔ Neo4j ↔ 双写）只改装配，不动业务代码。
 *
 * <p>历史实现（节点/边/证据三张表的读写）已迁至
 * {@code provider/graph/JdbcGraphStore}，逻辑等价，事务改为显式控制。
 * 这里只写包路径不写 {@code @link}：业务层不该在文档上指向具体实现，
 * 否则换实现时这份注释会变成新的负担。
 */
@Service
public class GraphService {

    private final GraphStore store;
    private final TracingService tracing;

    public GraphService(GraphStore store, TracingService tracing) {
        this.store = store;
        this.tracing = tracing;
    }

    public String storeName() {
        return store.name();
    }

    /** 供 /health/dependencies 探测图库是否真的可达（Neo4j 未启动时不应报 ready）。 */
    public boolean storeHealthy() {
        return store.healthy();
    }

    public GraphStore.PersistResult rebuild(String documentId, String artifactId,
                                            String markdown, MergedGraph graph) {
        return store.rebuild(documentId, artifactId, markdown, graph);
    }

    public GraphView graph(String documentId) {
        return store.load(documentId);
    }

    /** 以某实体为起点取 N 跳连通子图。depth 由存储实现裁剪到安全范围。 */
    public GraphView neighbors(String documentId, String entityId, int depth) {
        // 多跳查询是唯一可能"越跳越慢"的接口，单独给一个 span 才能看出跳数与耗时的关系
        return tracing.span("graph.neighbors", "graph", "graph-store",
                Map.of("documentId", documentId, "depth", depth, "entityId", entityId),
                () -> store.neighbors(documentId, entityId, depth));
    }

    public List<EvidenceEntity> evidences(String documentId, int limit) {
        return store.evidences(documentId, limit);
    }

    public void delete(String documentId) {
        store.delete(documentId);
    }
}
