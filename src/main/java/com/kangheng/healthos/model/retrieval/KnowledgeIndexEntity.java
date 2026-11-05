package com.kangheng.healthos.model.retrieval;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 外部知识库索引登记表。
 *
 * <p>为什么需要这张表而不是每次现查 RAGFlow：重建索引时要先删旧数据集，
 * 而 RAGFlow 的"按名字查找数据集"接口在不同版本上行为不一致。
 * 把 {@code datasetId} 记在自己这边，重建路径就只依赖确定性的记录。
 */
@Data
@TableName("knowledge_index")
public class KnowledgeIndexEntity {

    @TableId
    private String documentId;

    private String provider;

    private String datasetId;

    private String externalDocId;

    private Integer chunkCount;

    private String status;

    private String detail;

    private OffsetDateTime updatedAt;
}
