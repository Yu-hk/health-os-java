package com.kangheng.healthos.provider.graph;

import java.util.List;

import com.kangheng.healthos.spi.graph.MergedGraph;
import com.kangheng.healthos.model.graph.EvidenceEntity;
import com.kangheng.healthos.model.graph.GraphView;
import com.kangheng.healthos.spi.graph.GraphStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 双写图存储：PostgreSQL 与 Neo4j 各存一份。
 *
 * <p>分工是明确的：
 * <ul>
 *   <li><b>PG 是权威</b>——证据的字符偏移、与文档/作业的外键关系只在关系库里成立；</li>
 *   <li><b>Neo4j 是图查询引擎</b>——多跳邻域这类变长路径查询在关系库里只能靠内存 BFS 硬凑。</li>
 * </ul>
 *
 * <p>失败策略：PG 写失败直接抛（图谱重建本身失败）；Neo4j 写失败只记录 note 不抛——
 * 因为此时数据仍然完整可用，只是丧失了多跳查询能力。反过来让一次 Neo4j 抖动
 * 把整条典籍流水线判失败，代价过高。
 */
public class DualGraphStore implements GraphStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DualGraphStore.class);

    private final JdbcGraphStore local;
    private final Neo4jGraphStore remote;

    public DualGraphStore(JdbcGraphStore local, Neo4jGraphStore remote) {
        this.local = local;
        this.remote = remote;
    }

    @Override
    public String name() {
        return "dual";
    }

    @Override
    public PersistResult rebuild(String documentId, String artifactId, String markdown,
                                 MergedGraph graph) {
        PersistResult localResult = local.rebuild(documentId, artifactId, markdown, graph);
        try {
            PersistResult remoteResult = remote.rebuild(documentId, artifactId, markdown, graph);
            return new PersistResult(remoteResult.entities(), remoteResult.relations(),
                    remoteResult.evidences(), remoteResult.droppedRelations(), null);
        } catch (RuntimeException ex) {
            log.warn("Neo4j 写入失败，图仅存于 PostgreSQL doc={}: {}", documentId, ex.getMessage());
            return new PersistResult(localResult.entities(), localResult.relations(),
                    localResult.evidences(), localResult.droppedRelations(),
                    "neo4j 写入失败，已降级为 PostgreSQL 单存: " + ex.getMessage());
        }
    }

    @Override
    public GraphView load(String documentId) {
        try {
            GraphView view = remote.load(documentId);
            // 外部图库可能滞后于 PG（上一次写失败），空结果要回落而不是当成"图是空的"
            if (!view.nodes().isEmpty() || !view.edges().isEmpty()) {
                return view;
            }
            log.debug("Neo4j 中该文档没有图数据，回落 PostgreSQL doc={}", documentId);
        } catch (RuntimeException ex) {
            log.warn("Neo4j 查询失败，回落 PostgreSQL doc={}: {}", documentId, ex.getMessage());
        }
        return local.load(documentId);
    }

    @Override
    public GraphView neighbors(String documentId, String entityId, int depth) {
        try {
            return remote.neighbors(documentId, entityId, depth);
        } catch (RuntimeException ex) {
            log.warn("Neo4j 多跳查询失败，回落 PostgreSQL 内存 BFS doc={}: {}", documentId, ex.getMessage());
            return local.neighbors(documentId, entityId, depth);
        }
    }

    @Override
    public List<EvidenceEntity> evidences(String documentId, int limit) {
        return local.evidences(documentId, limit);
    }

    @Override
    public void delete(String documentId) {
        local.delete(documentId);
        try {
            remote.delete(documentId);
        } catch (RuntimeException ex) {
            log.warn("Neo4j 删除失败 doc={}: {}", documentId, ex.getMessage());
        }
    }

    @Override
    public boolean healthy() {
        return local.healthy() && remote.healthy();
    }

    @Override
    public void close() {
        remote.close();
    }
}
