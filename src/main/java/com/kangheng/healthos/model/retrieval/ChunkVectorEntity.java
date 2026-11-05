package com.kangheng.healthos.model.retrieval;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 切片向量。
 *
 * <p><b>两列存同一份向量，各有各的用处</b>：{@code vector} 是 JSON 文本，任何 Postgres
 * 都能存，检索时取回内存在 Java 侧算余弦——它是 pgvector 不可用时的唯一退路；
 * {@code embedding} 是定长 {@code vector(n)} 列，只有装了 pgvector 才有，用来建 HNSW 索引。
 * 两列都写（{@code embedding} 在扩展不可用时为空）是刻意的：降级路径能真的跑起来，
 * 而不是"理论上可以降级"。
 *
 * <p>{@code embedding} 故意<b>不映射到这个类</b>：MyBatis-Plus 的通用 insert 会把字段按
 * varchar 写进去，撞上 vector 类型直接报错。定长列只在
 * {@code ChunkVectorMapper#upsertWithEmbedding} 里用 {@code ?::vector} 显式写入。
 *
 * <p><b>为什么按 {@code (chunk_id, model)} 唯一</b>：换模型后旧向量不能被覆盖。
 * 一是回滚时还能用回去，二是"新模型只索引了一半"时旧向量仍然可查，
 * 不会出现"检索忽然全空"这种最难排查的状态。
 */
@Data
@TableName("chunk_vector")
public class ChunkVectorEntity {

    @TableId
    private String id;

    private String chunkId;

    private String documentId;

    /** 形如 {@code ollama:bge-m3}。 */
    private String model;

    private Integer dimension;

    /** JSON 数组，已 L2 归一化。 */
    private String vector;

    /** 正文哈希：正文变了就重算，没变就跳过。 */
    private String contentHash;

    private OffsetDateTime createdAt;
}
