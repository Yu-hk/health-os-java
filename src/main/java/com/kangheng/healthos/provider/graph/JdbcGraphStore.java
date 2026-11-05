package com.kangheng.healthos.provider.graph;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.kangheng.healthos.spi.graph.EntityNames;
import com.kangheng.healthos.spi.graph.MergedGraph;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.mapper.graph.EvidenceMapper;
import com.kangheng.healthos.mapper.graph.GraphEdgeMapper;
import com.kangheng.healthos.mapper.graph.GraphNodeMapper;
import com.kangheng.healthos.model.graph.EvidenceEntity;
import com.kangheng.healthos.model.graph.GraphEdgeEntity;
import com.kangheng.healthos.model.graph.GraphNodeEntity;
import com.kangheng.healthos.model.graph.GraphView;
import com.kangheng.healthos.text.EvidenceLocator;
import com.kangheng.healthos.spi.graph.GraphStore;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL 图存储。
 *
 * <p>节点、边、证据三张表都落在关系库里。这样做的好处是"证据外键"和文档事务天然一致：
 * 图谱重建失败时证据不会留下孤儿行。
 *
 * <p>事务用 {@link TransactionTemplate} 显式控制，而不是 {@code @Transactional}：
 * 本类由配置类直接 new 出来，不经过 Spring 代理，注解式事务在这里不会生效——
 * 这是一个容易静默出错的地方，用显式事务避免"以为有事务其实没有"。
 */
public class JdbcGraphStore implements GraphStore {

    private final GraphNodeMapper nodeMapper;
    private final GraphEdgeMapper edgeMapper;
    private final EvidenceMapper evidenceMapper;
    private final TransactionTemplate transactionTemplate;

    public JdbcGraphStore(GraphNodeMapper nodeMapper,
                          GraphEdgeMapper edgeMapper,
                          EvidenceMapper evidenceMapper,
                          PlatformTransactionManager transactionManager) {
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.evidenceMapper = evidenceMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public String name() {
        return "jdbc";
    }

    @Override
    public PersistResult rebuild(String documentId, String artifactId, String markdown,
                                 MergedGraph graph) {
        PersistResult result = transactionTemplate.execute(status -> doRebuild(
                documentId, artifactId, markdown, graph));
        return result == null ? new PersistResult(0, 0, 0, 0) : result;
    }

    private PersistResult doRebuild(String documentId, String artifactId, String markdown,
                                    MergedGraph graph) {
        edgeMapper.delete(new LambdaQueryWrapper<GraphEdgeEntity>()
                .eq(GraphEdgeEntity::getDocumentId, documentId));
        nodeMapper.delete(new LambdaQueryWrapper<GraphNodeEntity>()
                .eq(GraphNodeEntity::getDocumentId, documentId));
        evidenceMapper.delete(new LambdaQueryWrapper<EvidenceEntity>()
                .eq(EvidenceEntity::getDocumentId, documentId));

        Map<String, GraphNodeEntity> nodesByKey = new LinkedHashMap<>();
        for (var entity : graph.entities()) {
            String normalized = EntityNames.normalize(entity.name());
            if (normalized.isEmpty()) {
                continue;
            }
            String key = entity.type() + "|" + normalized;
            if (nodesByKey.containsKey(key)) {
                continue;
            }
            GraphNodeEntity node = new GraphNodeEntity();
            node.setId(Ids.stableId("gnd", documentId, key));
            node.setDocumentId(documentId);
            node.setName(entity.name().trim());
            node.setNormalizedName(normalized);
            node.setEntityType(entity.type() == null ? "CONCEPT" : entity.type());
            node.setAliases(Json.write(entity.aliases()));
            node.setCreatedAt(OffsetDateTime.now());
            nodeMapper.insert(node);
            nodesByKey.put(key, node);
        }

        int evidenceCount = 0;
        int relationCount = 0;
        int dropped = 0;
        Map<String, EvidenceEntity> evidenceByQuote = new HashMap<>();

        for (var relation : graph.relations()) {
            GraphNodeEntity source = ensureNode(nodesByKey, documentId, relation.source(), "CONCEPT");
            GraphNodeEntity target = ensureNode(nodesByKey, documentId, relation.target(), "CONCEPT");
            if (source == null || target == null) {
                dropped++;
                continue;
            }
            String quote = relation.evidence() == null ? "" : relation.evidence().trim();
            Optional<EvidenceLocator.Located> located = EvidenceLocator.locate(markdown, quote);
            if (located.isEmpty()) {
                dropped++;
                continue;
            }
            EvidenceLocator.Located hit = located.get();
            EvidenceEntity evidence = evidenceByQuote.get(hit.quote());
            if (evidence == null) {
                evidence = new EvidenceEntity();
                evidence.setId(Ids.stableId("ev", documentId, String.valueOf(hit.charStart()), hit.quote()));
                evidence.setDocumentId(documentId);
                evidence.setArtifactId(artifactId);
                evidence.setQuote(hit.quote());
                evidence.setCharStart(hit.charStart());
                evidence.setCharEnd(hit.charEnd());
                evidence.setPageFrom(hit.page());
                evidence.setPageTo(hit.page());
                evidence.setConfidence(BigDecimal.valueOf(relation.confidence()));
                evidence.setCreatedAt(OffsetDateTime.now());
                evidenceMapper.insert(evidence);
                evidenceByQuote.put(hit.quote(), evidence);
                evidenceCount++;
            }

            GraphEdgeEntity edge = new GraphEdgeEntity();
            edge.setId(Ids.stableId("ged", documentId, source.getId(), target.getId(), relation.type()));
            edge.setDocumentId(documentId);
            edge.setSourceEntityId(source.getId());
            edge.setTargetEntityId(target.getId());
            edge.setRelationType(relation.type() == null ? "RELATED" : relation.type());
            edge.setConfidence(BigDecimal.valueOf(relation.confidence()));
            edge.setEvidenceId(evidence.getId());
            edge.setCreatedAt(OffsetDateTime.now());
            edgeMapper.insert(edge);
            relationCount++;
        }
        return new PersistResult(nodesByKey.size(), relationCount, evidenceCount, dropped);
    }

    @Override
    public GraphView load(String documentId) {
        List<GraphNodeEntity> nodes = nodeMapper.selectList(new LambdaQueryWrapper<GraphNodeEntity>()
                .eq(GraphNodeEntity::getDocumentId, documentId)
                .orderByAsc(GraphNodeEntity::getCreatedAt));
        List<GraphEdgeEntity> edges = edgeMapper.selectList(new LambdaQueryWrapper<GraphEdgeEntity>()
                .eq(GraphEdgeEntity::getDocumentId, documentId)
                .orderByAsc(GraphEdgeEntity::getCreatedAt));
        Map<String, EvidenceEntity> evidences = new HashMap<>();
        for (EvidenceEntity evidence : evidenceMapper.selectList(new LambdaQueryWrapper<EvidenceEntity>()
                .eq(EvidenceEntity::getDocumentId, documentId))) {
            evidences.put(evidence.getId(), evidence);
        }
        return toView(documentId, nodes, edges, evidences);
    }

    @Override
    public List<EvidenceEntity> evidences(String documentId, int limit) {
        return evidenceMapper.findTopByDocument(documentId, limit);
    }

    /**
     * 多跳邻域。
     *
     * <p>关系库没有原生图遍历，这里在内存里做双向 BFS。单文档实体量级在千以内，
     * 一次性载入的代价远小于递归 CTE 的复杂度——但这也是选择 Neo4j 的理由：
     * 一旦需要跨文档查询"某味药在全部典籍中的关联"，内存 BFS 就不成立了。
     */
    @Override
    public GraphView neighbors(String documentId, String entityId, int depth) {
        GraphView full = load(documentId);
        Map<String, GraphView.NodeView> nodes = new LinkedHashMap<>();
        for (GraphView.NodeView node : full.nodes()) {
            nodes.put(node.id(), node);
        }
        if (!nodes.containsKey(entityId)) {
            return new GraphView(documentId, List.of(), List.of());
        }
        Map<String, List<GraphView.EdgeView>> adjacency = new HashMap<>();
        for (GraphView.EdgeView edge : full.edges()) {
            adjacency.computeIfAbsent(edge.sourceId(), key -> new ArrayList<>()).add(edge);
            adjacency.computeIfAbsent(edge.targetId(), key -> new ArrayList<>()).add(edge);
        }

        Set<String> visited = new LinkedHashSet<>();
        visited.add(entityId);
        Deque<String> frontier = new ArrayDeque<>();
        frontier.add(entityId);
        Set<String> edgeIds = new LinkedHashSet<>();
        for (int level = 0; level < depth && !frontier.isEmpty(); level++) {
            int size = frontier.size();
            for (int i = 0; i < size; i++) {
                String current = frontier.poll();
                for (GraphView.EdgeView edge : adjacency.getOrDefault(current, List.of())) {
                    edgeIds.add(edge.id());
                    String next = edge.sourceId().equals(current) ? edge.targetId() : edge.sourceId();
                    if (visited.add(next)) {
                        frontier.add(next);
                    }
                }
            }
        }

        List<GraphView.NodeView> nodeViews = new ArrayList<>();
        for (String id : visited) {
            GraphView.NodeView node = nodes.get(id);
            if (node != null) {
                nodeViews.add(node);
            }
        }
        List<GraphView.EdgeView> edgeViews = full.edges().stream()
                .filter(edge -> edgeIds.contains(edge.id()))
                .toList();
        return new GraphView(documentId, nodeViews, edgeViews);
    }

    @Override
    public void delete(String documentId) {
        transactionTemplate.executeWithoutResult(status -> {
            edgeMapper.delete(new LambdaQueryWrapper<GraphEdgeEntity>()
                    .eq(GraphEdgeEntity::getDocumentId, documentId));
            nodeMapper.delete(new LambdaQueryWrapper<GraphNodeEntity>()
                    .eq(GraphNodeEntity::getDocumentId, documentId));
            evidenceMapper.delete(new LambdaQueryWrapper<EvidenceEntity>()
                    .eq(EvidenceEntity::getDocumentId, documentId));
        });
    }

    // ---------------------------------------------------------------- 内部

    private GraphNodeEntity ensureNode(Map<String, GraphNodeEntity> nodesByKey, String documentId,
                                       String name, String fallbackType) {
        String normalized = EntityNames.normalize(name);
        if (normalized.isEmpty()) {
            return null;
        }
        for (String type : List.of("FORMULA", "HERB", "SYMPTOM", "EFFECT", "MERIDIAN", "CONCEPT")) {
            GraphNodeEntity node = nodesByKey.get(type + "|" + normalized);
            if (node != null) {
                return node;
            }
        }
        String key = fallbackType + "|" + normalized;
        GraphNodeEntity node = new GraphNodeEntity();
        node.setId(Ids.stableId("gnd", documentId, key));
        node.setDocumentId(documentId);
        node.setName(name == null ? "" : name.trim());
        node.setNormalizedName(normalized);
        node.setEntityType(fallbackType);
        node.setAliases("[]");
        node.setCreatedAt(OffsetDateTime.now());
        nodeMapper.insert(node);
        nodesByKey.put(key, node);
        return node;
    }

    static GraphView toView(String documentId, List<GraphNodeEntity> nodes, List<GraphEdgeEntity> edges,
                            Map<String, EvidenceEntity> evidences) {
        List<GraphView.NodeView> nodeViews = new ArrayList<>();
        for (GraphNodeEntity node : nodes) {
            List<String> aliases = Json.read(node.getAliases(), List.class);
            nodeViews.add(new GraphView.NodeView(node.getId(), node.getName(), node.getEntityType(),
                    aliases == null ? List.of() : aliases));
        }
        List<GraphView.EdgeView> edgeViews = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (GraphEdgeEntity edge : edges) {
            if (!seen.add(edge.getId())) {
                continue;
            }
            EvidenceEntity evidence = evidences.get(edge.getEvidenceId());
            edgeViews.add(new GraphView.EdgeView(
                    edge.getId(),
                    edge.getSourceEntityId(),
                    edge.getTargetEntityId(),
                    edge.getRelationType(),
                    edge.getConfidence() == null ? 0 : edge.getConfidence().doubleValue(),
                    evidence == null ? null : evidence.getQuote(),
                    evidence == null ? null : evidence.getPageFrom()));
        }
        return new GraphView(documentId, nodeViews, edgeViews);
    }
}
