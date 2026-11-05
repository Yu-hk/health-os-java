package com.kangheng.healthos.model.retrieval;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 切片向量。
 *
 * <p><b>为什么向量存成 TEXT 而不是 {@code vector(n)}</b>：官方 {@code postgres:16} 镜像不带
 * pgvector，装扩展要重建镜像；本切片的候选集只有几十到几百条，用 JSON 数组 + Java 侧余弦
 * 完全够用，而且 {@code psql} 里能直接看懂。真上 pgvector 时把这一列换成 {@code vector(1024)}
 * 并加 HNSW 索引，改的只是 {@code ChunkVectorService} 的读写两句 SQL。
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
