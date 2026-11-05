package com.kangheng.healthos.provider.graph;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.kangheng.healthos.spi.graph.EntityNames;
import com.kangheng.healthos.spi.graph.MergedGraph;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.mapper.graph.EvidenceMapper;
import com.kangheng.healthos.model.graph.EvidenceEntity;
import com.kangheng.healthos.model.graph.GraphView;
import com.kangheng.healthos.text.EvidenceLocator;
import com.kangheng.healthos.spi.graph.GraphStore;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.exceptions.SessionExpiredException;
import org.neo4j.driver.types.Node;import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Neo4j 图存储。
 *
 * <p>写入是"证据先落 PG、图再落 Neo4j"。顺序不是随意的：反过来一旦 Neo4j 成功而 PG 失败，
 * 边上就会挂一个查不到的 evidenceId；而当前顺序最坏只会多出一条没人引用的证据行——
 * 多余证据不影响任何结论，缺失证据却会让"可溯源"失效。
 *
 * <p>边同时冗余存 {@code evidenceQuote} 与 {@code pageFrom}，让图查询一次就能拿到出处，
 * 不必为每次读图回表。冗余的前提是写入前已经用 {@link EvidenceLocator} 在原文里定位过——
 * 定位失败的边不会写进来。
 */
public class Neo4jGraphStore implements GraphStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Neo4jGraphStore.class);

    private static final String LABEL = "HealthEntity";
    private static final String REL = "RELATION";

    private static final String CYPHER_CLEAR =
            "MATCH (n:" + LABEL + " {documentId: $documentId}) DETACH DELETE n";

    private static final String CYPHER_WRITE_NODES =
            "UNWIND $rows AS row "
                    + "MERGE (n:" + LABEL + " {id: row.id}) "
                    + "SET n.documentId = row.documentId, n.name = row.name, "
                    + "    n.normalizedName = row.normalizedName, n.entityType = row.entityType, "
                    + "    n.aliases = row.aliases";

    private static final String CYPHER_WRITE_EDGES =
            "UNWIND $rows AS row "
                    + "MATCH (s:" + LABEL + " {id: row.sourceId}) "
                    + "MATCH (t:" + LABEL + " {id: row.targetId}) "
                    + "MERGE (s)-[r:" + REL + " {id: row.id}]->(t) "
                    + "SET r.documentId = row.documentId, r.type = row.type, "
                    + "    r.confidence = row.confidence, r.evidenceId = row.evidenceId, "
                    + "    r.evidenceQuote = row.evidenceQuote, r.pageFrom = row.pageFrom";

    private static final String CYPHER_LOAD_NODES =
            "MATCH (n:" + LABEL + " {documentId: $documentId}) "
                    + "RETURN n.id AS id, n.name AS name, n.entityType AS type, n.aliases AS aliases";

    private static final String CYPHER_LOAD_EDGES =
            "MATCH (s:" + LABEL + " {documentId: $documentId})-[r:" + REL + "]->(t:" + LABEL + " {documentId: $documentId}) "
                    + "RETURN r.id AS id, s.id AS sourceId, t.id AS targetId, r.type AS type, "
                    + "       r.confidence AS confidence, r.evidenceQuote AS quote, r.pageFrom AS page";

    /**
     * 多跳节点集合。跳数 {@code *1..n} 无法参数化，只能拼接，因此调用方必须先把 depth 裁剪成合法整数。
     */
    private static final String CYPHER_NEIGHBOR_NODES_PREFIX =
            "MATCH (root:" + LABEL + " {id: $entityId, documentId: $documentId}) "
                    + "OPTIONAL MATCH (root)-[*1..";

    private static final String CYPHER_NEIGHBOR_NODES_SUFFIX =
            "]-(other:" + LABEL + " {documentId: $documentId}) "
                    + "RETURN root, collect(DISTINCT other) AS others";

    /**
     * 多跳边集合。直接返回 {@code startNode(rel).id} / {@code endNode(rel).id}，
     * 避免拿 elementId 再去回查一次全图。
     */
    private static final String CYPHER_NEIGHBOR_EDGES_PREFIX =
            "MATCH (root:" + LABEL + " {id: $entityId, documentId: $documentId}) "
                    + "MATCH (root)-[rels*1..";

    private static final String CYPHER_NEIGHBOR_EDGES_SUFFIX =
            "]-(other:" + LABEL + " {documentId: $documentId}) "
                    + "UNWIND rels AS rel "
                    + "RETURN DISTINCT rel.id AS id, rel.type AS type, rel.confidence AS confidence, "
                    + "  rel.evidenceQuote AS quote, rel.pageFrom AS page, "
                    + "  startNode(rel).id AS sourceId, endNode(rel).id AS targetId";

    private final Driver driver;
    private final String database;
    private final int maxDepth;
    private final EvidenceMapper evidenceMapper;
    private final TransactionTemplate transactionTemplate;

    /**
     * 熔断时间戳。Neo4j 挂掉时 driver 会在连接超时 + 事务重试上耗掉数十秒，
     * 每个请求都等一遍会让 fallback 变成"更慢的失败"。
     * 连续失败后短路一段时间，直接让上层走 PostgreSQL——这才是降级该有的行为。
     */
    private final java.util.concurrent.atomic.AtomicLong unavailableUntil =
            new java.util.concurrent.atomic.AtomicLong(0);

    private static final long CIRCUIT_COOLDOWN_MS = 30_000;

    public Neo4jGraphStore(HealthOsProperties.Neo4j config,
                           EvidenceMapper evidenceMapper,
                           PlatformTransactionManager transactionManager) {
        this.database = config.getDatabase() == null || config.getDatabase().isBlank()
                ? "neo4j" : config.getDatabase();
        this.maxDepth = Math.max(1, config.getMaxDepth());
        this.evidenceMapper = evidenceMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.driver = GraphDatabase.driver(config.getUri(),
                AuthTokens.basic(config.getUsername(), config.getPassword()),
                Config.builder()
                        .withConnectionTimeout(config.getConnectionTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                        .withMaxConnectionPoolSize(8)
                        .withConnectionLivenessCheckTimeout(30_000, java.util.concurrent.TimeUnit.MILLISECONDS)
                        // 默认 30s 的事务级重试会让"快速降级"失效，这里压到 3s
                        .withMaxTransactionRetryTime(3_000, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .build());
    }

    @Override
    public String name() {
        return "neo4j";
    }

    @Override
    public boolean healthy() {
        if (circuitOpen()) {
            return false;
        }
        try {
            driver.verifyConnectivity();
            unavailableUntil.set(0);
            return true;
        } catch (Exception ex) {
            markUnavailable();
            log.debug("Neo4j 不可达: {}", ex.getMessage());
            return false;
        }
    }

    /** 熔断期内直接失败，让上层立刻走降级路径，不再等连接超时。 */
    private boolean circuitOpen() {
        return System.currentTimeMillis() < unavailableUntil.get();
    }

    private void markUnavailable() {
        unavailableUntil.set(System.currentTimeMillis() + CIRCUIT_COOLDOWN_MS);
    }

    /**
     * 包装一次 driver 调用：只在"连不上"这类故障上熔断。
     * Cypher 语法错、约束冲突属于代码问题，必须原样抛出，否则会被误判成服务不可用。
     */
    private <T> T guarded(String operation, java.util.function.Supplier<T> call) {
        if (circuitOpen()) {
            throw new Neo4jUnavailableException("Neo4j 熔断中（" + operation + "），已直接降级");
        }
        try {
            T result = call.get();
            unavailableUntil.set(0);
            return result;
        } catch (ServiceUnavailableException | SessionExpiredException ex) {
            markUnavailable();
            throw new Neo4jUnavailableException("Neo4j " + operation + " 连接失败: " + ex.getMessage(), ex);
        }
    }

    /** 明确表示"图库当前不可用"，供上层捕获后回落 PostgreSQL。 */
    public static class Neo4jUnavailableException extends RuntimeException {

        public Neo4jUnavailableException(String message) {
            super(message);
        }

        public Neo4jUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @Override
    public void close() {
        driver.close();
    }

    // ---------------------------------------------------------------- 写入

    @Override
    public PersistResult rebuild(String documentId, String artifactId, String markdown,
                                 MergedGraph graph) {
        Prepared prepared = prepare(documentId, markdown, graph);
        guarded("rebuild", () -> withSession(session -> session.executeWrite(tx -> {
            tx.run(CYPHER_CLEAR, Map.of("documentId", documentId)).consume();
            if (!prepared.nodes().isEmpty()) {
                tx.run(CYPHER_WRITE_NODES, Map.of("rows", prepared.nodes())).consume();
            }
            if (!prepared.edges().isEmpty()) {
                tx.run(CYPHER_WRITE_EDGES, Map.of("rows", prepared.edges())).consume();
            }
            return null;
        })));
        persistEvidences(artifactId, prepared.evidences());
        log.info("Neo4j 图写入完成 doc={} nodes={} edges={} dropped={}",
                documentId, prepared.nodes().size(), prepared.edges().size(), prepared.dropped());
        return new PersistResult(prepared.nodes().size(), prepared.edges().size(),
                prepared.evidences().size(), prepared.dropped());
    }

    private record Prepared(List<Map<String, Object>> nodes,
                            List<Map<String, Object>> edges,
                            List<EvidenceEntity> evidences,
                            int dropped) {
    }

    /**
     * 纯计算阶段：建节点、定位证据、连边。
     *
     * <p>与 JDBC 实现共用同一套 ID 生成规则，因此同一份抽取结果在两种存储下 ID 完全一致，
     * 可以交叉排查"图里有、库里没有"的情况。
     */
    private Prepared prepare(String documentId, String markdown, MergedGraph graph) {
        Map<String, Map<String, Object>> nodesByKey = new LinkedHashMap<>();
        for (var entity : graph.entities()) {
            String normalized = EntityNames.normalize(entity.name());
            if (normalized.isEmpty()) {
                continue;
            }
            String key = entity.type() + "|" + normalized;
            if (nodesByKey.containsKey(key)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", Ids.stableId("gnd", documentId, key));
            row.put("documentId", documentId);
            row.put("name", entity.name().trim());
            row.put("normalizedName", normalized);
            row.put("entityType", entity.type() == null ? "CONCEPT" : entity.type());
            row.put("aliases", Json.write(entity.aliases() == null ? List.of() : entity.aliases()));
            nodesByKey.put(key, row);
        }

        List<Map<String, Object>> edges = new ArrayList<>();
        List<EvidenceEntity> evidences = new ArrayList<>();
        Map<String, EvidenceEntity> evidenceByQuote = new HashMap<>();
        int dropped = 0;

        for (var relation : graph.relations()) {
            Map<String, Object> source = ensureNode(nodesByKey, documentId, relation.source(), "CONCEPT");
            Map<String, Object> target = ensureNode(nodesByKey, documentId, relation.target(), "CONCEPT");
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
                evidence.setQuote(hit.quote());
                evidence.setCharStart(hit.charStart());
                evidence.setCharEnd(hit.charEnd());
                evidence.setPageFrom(hit.page());
                evidence.setPageTo(hit.page());
                evidence.setConfidence(BigDecimal.valueOf(relation.confidence()));
                evidenceByQuote.put(hit.quote(), evidence);
                evidences.add(evidence);
            }

            String sourceId = String.valueOf(source.get("id"));
            String targetId = String.valueOf(target.get("id"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", Ids.stableId("ged", documentId, sourceId, targetId, relation.type()));
            row.put("documentId", documentId);
            row.put("sourceId", sourceId);
            row.put("targetId", targetId);
            row.put("type", relation.type() == null ? "RELATED" : relation.type());
            row.put("confidence", relation.confidence());
            row.put("evidenceId", evidence.getId());
            row.put("evidenceQuote", hit.quote());
            row.put("pageFrom", hit.page());
            edges.add(row);
        }
        return new Prepared(new ArrayList<>(nodesByKey.values()), edges, evidences, dropped);
    }

    private Map<String, Object> ensureNode(Map<String, Map<String, Object>> nodesByKey, String documentId,
                                           String name, String fallbackType) {
        String normalized = EntityNames.normalize(name);
        if (normalized.isEmpty()) {
            return null;
        }
        for (String type : List.of("FORMULA", "HERB", "SYMPTOM", "EFFECT", "MERIDIAN", "CONCEPT")) {
            Map<String, Object> node = nodesByKey.get(type + "|" + normalized);
            if (node != null) {
                return node;
            }
        }
        String key = fallbackType + "|" + normalized;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", Ids.stableId("gnd", documentId, key));
        row.put("documentId", documentId);
        row.put("name", name == null ? "" : name.trim());
        row.put("normalizedName", normalized);
        row.put("entityType", fallbackType);
        row.put("aliases", "[]");
        nodesByKey.put(key, row);
        return row;
    }

    private void persistEvidences(String artifactId, List<EvidenceEntity> evidences) {
        if (evidences.isEmpty()) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            for (EvidenceEntity evidence : evidences) {
                evidence.setArtifactId(artifactId);
                evidence.setCreatedAt(OffsetDateTime.now());
                // 重建场景下同一 quote 可能已存在，先删后插保证幂等
                evidenceMapper.deleteById(evidence.getId());
                evidenceMapper.insert(evidence);
            }
        });
    }

    // ---------------------------------------------------------------- 读取

    @Override
    public GraphView load(String documentId) {
        List<GraphView.NodeView> nodes = new ArrayList<>();
        List<GraphView.EdgeView> edges = new ArrayList<>();
        List<Record> nodeRecords = guarded("load-nodes", () -> withSession(session ->
                session.executeRead(tx -> tx.run(CYPHER_LOAD_NODES, Map.of("documentId", documentId)).list())));
        for (Record record : nodeRecords) {
            nodes.add(new GraphView.NodeView(
                    text(record.get("id")),
                    text(record.get("name")),
                    text(record.get("type")),
                    Json.readList(record.get("aliases").isNull() ? "[]" : record.get("aliases").asString(),
                            String.class)));
        }
        List<Record> edgeRecords = guarded("load-edges", () -> withSession(session ->
                session.executeRead(tx -> tx.run(CYPHER_LOAD_EDGES, Map.of("documentId", documentId)).list())));
        for (Record record : edgeRecords) {
            Value page = record.get("page");
            edges.add(new GraphView.EdgeView(
                    text(record.get("id")),
                    text(record.get("sourceId")),
                    text(record.get("targetId")),
                    text(record.get("type")),
                    record.get("confidence").isNull() ? 0 : record.get("confidence").asDouble(),
                    record.get("quote").isNull() ? null : record.get("quote").asString(),
                    page.isNull() ? null : page.asInt()));
        }
        return new GraphView(documentId, nodes, edges);
    }

    /**
     * 多跳邻域，用 Cypher 变长路径。
     *
     * <p>{@code *1..n} 的跳数在 Cypher 里<b>不能参数化</b>，只能拼进语句。
     * 因此这里把 depth 强制裁剪到 [1, maxDepth] 的整数范围后再拼接——
     * 这是本类唯一一处字符串拼 Cypher 的地方，边界校验是安全前提。
     */
    @Override
    public GraphView neighbors(String documentId, String entityId, int depth) {
        int bounded = Math.max(1, Math.min(depth, maxDepth));
        Map<String, Object> params = Map.of("documentId", documentId, "entityId", entityId);
        Map<String, GraphView.NodeView> nodes = new LinkedHashMap<>();
        List<GraphView.EdgeView> edges = new ArrayList<>();

        String nodesCypher = CYPHER_NEIGHBOR_NODES_PREFIX + bounded + CYPHER_NEIGHBOR_NODES_SUFFIX;
        List<Record> nodeRecords = guarded("neighbor-nodes", () -> withSession(session ->
                session.executeRead(tx -> tx.run(nodesCypher, params).list())));
        for (Record record : nodeRecords) {
            Value root = record.get("root");
            if (!root.isNull()) {
                putNode(nodes, root.asNode());
            }
            for (Value other : record.get("others").values()) {
                if (!other.isNull()) {
                    putNode(nodes, other.asNode());
                }
            }
        }
        if (nodes.isEmpty()) {
            return new GraphView(documentId, List.of(), List.of());
        }

        String edgesCypher = CYPHER_NEIGHBOR_EDGES_PREFIX + bounded + CYPHER_NEIGHBOR_EDGES_SUFFIX;
        List<Record> edgeRecords = guarded("neighbor-edges", () -> withSession(session ->
                session.executeRead(tx -> tx.run(edgesCypher, params).list())));
        for (Record record : edgeRecords) {
            Value page = record.get("page");
            edges.add(new GraphView.EdgeView(
                    text(record.get("id")),
                    text(record.get("sourceId")),
                    text(record.get("targetId")),
                    text(record.get("type")),
                    record.get("confidence").isNull() ? 0 : record.get("confidence").asDouble(),
                    record.get("quote").isNull() ? null : record.get("quote").asString(),
                    page.isNull() ? null : page.asInt()));
        }
        return new GraphView(documentId, new ArrayList<>(nodes.values()), edges);
    }

    private void putNode(Map<String, GraphView.NodeView> nodes, Node node) {
        String id = text(node.get("id"));
        if (id == null || nodes.containsKey(id)) {
            return;
        }
        nodes.put(id, new GraphView.NodeView(id, text(node.get("name")), text(node.get("entityType")),
                Json.readList(node.get("aliases").isNull() ? "[]" : node.get("aliases").asString(),
                        String.class)));
    }

    @Override
    public List<EvidenceEntity> evidences(String documentId, int limit) {
        return evidenceMapper.findTopByDocument(documentId, limit);
    }

    @Override
    public void delete(String documentId) {
        guarded("delete", () -> withSession(session -> session.executeWrite(tx -> {
            tx.run(CYPHER_CLEAR, Map.of("documentId", documentId)).consume();
            return null;
        })));
        evidenceMapper.delete(new LambdaQueryWrapper<EvidenceEntity>()
                .eq(EvidenceEntity::getDocumentId, documentId));
    }

    // ---------------------------------------------------------------- 工具

    private Session session() {
        return driver.session(org.neo4j.driver.SessionConfig.builder()
                .withDatabase(database)
                .build());
    }

    private <T> T withSession(java.util.function.Function<Session, T> action) {
        try (Session session = session()) {
            return action.apply(session);
        }
    }

    private static String text(Value value) {
        return value == null || value.isNull() ? null : value.asString();
    }
}
