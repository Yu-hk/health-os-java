package com.kangheng.healthos.provider.retrieval;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.kangheng.healthos.config.HealthOsProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * pgvector 索引的探测与建立（{@code chunk_vector.embedding} + HNSW）。
 *
 * <p><b>为什么 DDL 写在 Java 里而不是 {@code schema.sql}</b>：
 * <ol>
 *   <li>{@code CREATE EXTENSION} 需要超级用户或预装扩展，直接写进启动脚本会让
 *       "拿到的 Postgres 没装 pgvector" 变成整个应用起不来——这是把一个可选增强
 *       做成了硬依赖；</li>
 *   <li>Spring 的脚本切分器不认 {@code DO $$ ... $$} 的美元引号，写条件 DDL 会被
 *       从中间的 {@code ;} 切开，得到一堆语法错误。</li>
 * </ol>
 * 所以基础表仍在 {@code schema.sql}，<b>可选的那两列/索引由这里条件建</b>：
 * 装不上就降级，不留痕才是真问题。
 *
 * <p><b>为什么列要定长维度</b>：HNSW 索引要求 {@code vector(n)} 定长，{@code vector} 不定长
 * 建不了索引。维度取自 {@code healthos.embedding.dimension}，与已存在列的维度不一致时
 * <b>不动它</b>，直接降级——改维度意味着旧向量全部作废，这种决定不能由启动流程替人做。
 */
@Component
public class PgVectorIndex {

    private static final Logger log = LoggerFactory.getLogger(PgVectorIndex.class);

    /** 索引名固定，手工维护时对得上。 */
    public static final String INDEX_NAME = "ix_chunk_vector_ann";

    private final JdbcTemplate jdbc;
    private final HealthOsProperties properties;

    private final AtomicBoolean attempted = new AtomicBoolean(false);
    private volatile boolean available;
    /** 给健康检查看的口径：{@code pgvector:0.8.0(dim=1024,hnsw)} / {@code disabled:...} / {@code degraded:...}。 */
    private volatile String posture = "disabled:not-initialized";

    public PgVectorIndex(JdbcTemplate jdbc, HealthOsProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        ensure();
    }

    /** 能不能走 ANN 查询。探测只做一次，失败不重试——扩展不会在运行期自己长出来。 */
    public boolean available() {
        ensure();
        return available;
    }

    /** 健康检查口径。 */
    public String posture() {
        ensure();
        return posture;
    }

    /** 运行期发现 ANN 不可用（维度不符、插入报错）时主动降级，避免每条查询都失败一次。 */
    public void degrade(String reason) {
        if (available) {
            log.warn("pgvector ANN 索引降级：{}（检索改为取回向量在 Java 侧算余弦）", reason);
        }
        available = false;
        posture = "degraded:" + reason;
    }

    /** 测试用：一个永远不可用的实例，不需要连库。 */
    public static PgVectorIndex disabled() {
        PgVectorIndex index = new PgVectorIndex(null, new HealthOsProperties());
        index.attempted.set(true);
        index.available = false;
        index.posture = "disabled:test";
        return index;
    }

    private void ensure() {
        if (!attempted.compareAndSet(false, true)) {
            return;
        }
        String setting = properties.getRetrieval().getVectorIndex();
        if ("off".equalsIgnoreCase(setting)) {
            posture = "disabled:off";
            return;
        }
        if (!"ollama".equalsIgnoreCase(properties.getEmbedding().getProvider())) {
            // 没有向量化就没有可索引的东西；说清楚原因，别让人以为索引坏了
            posture = "disabled:embedding-off";
            return;
        }
        int dim = properties.getEmbedding().getDimension();
        if (dim <= 0) {
            posture = "disabled:dimension-unknown";
            return;
        }
        try {
            prepare(dim);
        } catch (RuntimeException ex) {
            // 不抛：装不上 pgvector 是环境问题，不该让应用起不来
            available = false;
            posture = "degraded:" + rootCause(ex);
            log.warn("pgvector 索引不可用（{}），检索退回 Java 侧余弦：{}", posture, ex.getMessage());
        }
    }

    private void prepare(int dim) {
        if (jdbc == null) {
            return;
        }
        Integer table = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND table_name = 'chunk_vector'",
                Integer.class);
        if (table == null || table == 0) {
            posture = "disabled:table-missing";
            return;
        }
        String version = extensionVersion();
        if (version == null) {
            if (!"auto".equalsIgnoreCase(properties.getRetrieval().getVectorIndex())) {
                posture = "disabled:extension-missing";
                log.warn("数据库没有 vector 扩展，且配置未允许自动安装（healthos.retrieval.vector-index={}）",
                        properties.getRetrieval().getVectorIndex());
                return;
            }
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
            version = extensionVersion();
            if (version == null) {
                posture = "disabled:extension-missing";
                return;
            }
            log.info("已安装 vector 扩展 version={}", version);
        }

        String columnType = embeddingColumnType();
        if (columnType == null) {
            jdbc.execute("ALTER TABLE chunk_vector ADD COLUMN embedding vector(" + dim + ")");
            log.info("已新增 chunk_vector.embedding vector({})", dim);
        } else {
            Integer existing = declaredDimension(columnType);
            if (existing == null || existing != dim) {
                // 维度不一致时绝不 DROP COLUMN：里面是别人已经算好的向量
                posture = "degraded:dim_mismatch(existing=" + columnType + ",configured=" + dim + ")";
                log.warn("chunk_vector.embedding 维度与配置不一致（{} vs {}），跳过 ANN", columnType, dim);
                return;
            }
        }

        jdbc.execute("CREATE INDEX IF NOT EXISTS " + INDEX_NAME
                + " ON chunk_vector USING hnsw (embedding vector_cosine_ops)");
        available = true;
        posture = "pgvector:" + version + "(dim=" + dim + ",hnsw)";
        log.info("pgvector ANN 索引就绪：{}", posture);
    }

    private String extensionVersion() {
        List<String> rows = jdbc.queryForList(
                "SELECT extversion FROM pg_extension WHERE extname = 'vector'", String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String embeddingColumnType() {
        List<String> rows = jdbc.queryForList(
                "SELECT format_type(atttypid, atttypmod) FROM pg_attribute "
                        + "WHERE attrelid = to_regclass('chunk_vector') AND attname = 'embedding' "
                        + "AND NOT attisdropped",
                String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 从 {@code vector(1024)} 里取出 1024；不定长或无维度返回 null。 */
    static Integer declaredDimension(String columnType) {
        if (columnType == null) {
            return null;
        }
        int open = columnType.indexOf('(');
        int close = columnType.indexOf(')', open + 1);
        if (open < 0 || close < 0) {
            return null;
        }
        try {
            return Integer.valueOf(columnType.substring(open + 1, close).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String rootCause(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
        return message.length() > 120 ? message.substring(0, 120) : message;
    }
}
