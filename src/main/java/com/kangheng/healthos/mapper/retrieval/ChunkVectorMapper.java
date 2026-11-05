package com.kangheng.healthos.mapper.retrieval;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.kangheng.healthos.model.retrieval.ChunkVectorEntity;
import com.kangheng.healthos.model.retrieval.ChunkVectorScore;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 切片向量读写。
 *
 * <p>方言无关的部分（{@link #listByDocument}、{@link #countByDocument}）与 pgvector 专用的
 * 部分（{@link #upsertWithEmbedding}、{@link #searchTopK}）分开写：
 * 前两条在任何 Postgres 上都能跑，后两条只有装了 vector 扩展才行，调用前必须先问
 * {@code PgVectorIndex#available()}。把它们混在一条"聪明"的 SQL 里，会让
 * "扩展没装"从一句明确的降级变成一堆待排查的 SQL 报错。
 *
 * <p>{@code ?::vector} 是这里唯一的方言痕迹。实测（PgJDBC 42.7）{@code setString} + {@code ?::vector}
 * 可用，因此不需要引入 {@code PGobject}，也就不必把驱动从 {@code runtime} 提到编译期。
 */
@Mapper
public interface ChunkVectorMapper extends BaseMapper<ChunkVectorEntity> {

    @Select("SELECT * FROM chunk_vector WHERE document_id = #{documentId} AND model = #{model} "
            + "ORDER BY chunk_id")
    List<ChunkVectorEntity> listByDocument(@Param("documentId") String documentId, @Param("model") String model);

    @Select("SELECT COUNT(*) FROM chunk_vector WHERE document_id = #{documentId} AND model = #{model}")
    int countByDocument(@Param("documentId") String documentId, @Param("model") String model);

    /**
     * 写一行并同时写定长向量列。{@code vector} 文本列仍然照写——它是 pgvector 不可用时的
     * 唯一退路，也是 {@code psql} 里能直接看懂的那一份。
     *
     * <p>用 {@code ON CONFLICT} 而不是"先删再插"：删插是两条语句、中间有窗口，
     * 并发索引同一篇文档时会撞唯一键。单条 upsert 只是"改一行一次"，不触碰
     * PostgreSQL「一条语句不能对同一行改两次」的坑。
     */
    @Insert("""
            INSERT INTO chunk_vector
                (id, chunk_id, document_id, model, dimension, vector, content_hash, created_at, embedding)
            VALUES
                (#{row.id}, #{row.chunkId}, #{row.documentId}, #{row.model}, #{row.dimension}, #{row.vector},
                 #{row.contentHash}, #{row.createdAt}, #{row.vector}::vector)
            ON CONFLICT (chunk_id, model) DO UPDATE SET
                id = EXCLUDED.id,
                document_id = EXCLUDED.document_id,
                dimension = EXCLUDED.dimension,
                vector = EXCLUDED.vector,
                content_hash = EXCLUDED.content_hash,
                created_at = EXCLUDED.created_at,
                embedding = EXCLUDED.embedding
            """)
    int upsertWithEmbedding(@Param("row") ChunkVectorEntity row);

    /**
     * 本文档内与 {@code query} 最相近的前 {@code limit} 条切片。
     *
     * <p>写成子查询是为了让向量字面量只出现一次——同一份 {@code ?::vector} 写两遍
     * （SELECT 里一次、ORDER BY 里一次）就要传两遍参数，没人保证它们一定相同。
     *
     * <p>{@code score} 用 {@code 1 - 余弦距离}，与 Java 侧 {@code EmbeddingModel.cosine} 同口径；
     * 负值由调用方截断到 0。
     */
    @Select("""
            SELECT chunk_id, 1 - dist AS score FROM (
                SELECT chunk_id, embedding <=> #{query}::vector AS dist
                FROM chunk_vector
                WHERE document_id = #{documentId} AND model = #{model} AND embedding IS NOT NULL
            ) ranked
            ORDER BY dist
            LIMIT #{limit}
            """)
    List<ChunkVectorScore> searchTopK(@Param("documentId") String documentId,
                                      @Param("model") String model,
                                      @Param("query") String query,
                                      @Param("limit") int limit);

    /** 已写入定长向量的行数：用来判断"索引建了但历史向量还没回填"。 */
    @Select("SELECT COUNT(*) FROM chunk_vector WHERE document_id = #{documentId} AND model = #{model} "
            + "AND embedding IS NOT NULL")
    int countIndexed(@Param("documentId") String documentId, @Param("model") String model);

    /**
     * 有文本向量、没有定长向量的切片。
     *
     * <p>这是"索引晚于向量"的必然产物：{@code embedding} 列是后来加的，历史行只有 JSON 文本。
     * 只看 {@code content_hash} 判断要不要重算会让这些行永远补不上——正文没变，
     * 于是每次都被判定为"已是最新"，而 ANN 查询又把 NULL 行过滤掉了。
     */
    @Select("SELECT chunk_id FROM chunk_vector WHERE document_id = #{documentId} AND model = #{model} "
            + "AND embedding IS NULL")
    List<String> listMissingEmbedding(@Param("documentId") String documentId, @Param("model") String model);
}
