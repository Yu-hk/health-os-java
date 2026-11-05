package com.kangheng.healthos.spi.graph;

import java.util.List;

import com.kangheng.healthos.model.graph.GraphView;
import com.kangheng.healthos.model.graph.EvidenceEntity;

/**
 * 图存储抽象。
 *
 * <p>两种实现承担不同职责，但都必须满足同一条硬约束：
 * <b>每条边都要挂到一条能在原文定位的证据上</b>，定位失败的关系整条丢弃。
 * 这条约束不因为换存储而放松——它是"医疗结论必须有出处"的直接体现。
 *
 * <p>节点 ID 用 {@code stableId("gnd", documentId, type|名称)} 生成，两个实现共用同一套 ID，
 * 因此可以从 Neo4j 的结果直接反查 PG 的 evidence 表。
 */
public interface GraphStore {

    /** jdbc / neo4j / dual，写入流水线节点 detail。 */
    String name();

    /** 全量重建某文档的图。幂等：重复执行结果一致。 */
    PersistResult rebuild(String documentId, String artifactId, String markdown,
                          MergedGraph graph);

    GraphView load(String documentId);

    List<EvidenceEntity> evidences(String documentId, int limit);

    /**
     * 以某实体为起点取 N 跳连通子图。
     *
     * @param depth 1..maxDepth，实现内部必须做边界校验（Cypher 的跳数无法参数化，只能拼接）
     */
    GraphView neighbors(String documentId, String entityId, int depth);

    default void delete(String documentId) {
    }

    default boolean healthy() {
        return true;
    }

    /**
     * @param note 非致命说明。dual 模式下 Neo4j 写失败但 PG 写成功时写在这里——
     *             降级可以接受，但必须让 detail 里看得见"图没进 Neo4j"
     */
    record PersistResult(int entities, int relations, int evidences, int droppedRelations, String note) {

        public PersistResult(int entities, int relations, int evidences, int droppedRelations) {
            this(entities, relations, evidences, droppedRelations, null);
        }
    }
}
