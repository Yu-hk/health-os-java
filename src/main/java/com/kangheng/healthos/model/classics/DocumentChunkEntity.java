package com.kangheng.healthos.model.classics;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 检索切片。原项目写到 RAGFlow，本切片落本地库以支持无外部依赖的检索。 */
@Data
@TableName("document_chunk")
public class DocumentChunkEntity {

    @TableId(type = IdType.INPUT)
    private String id;
    private String documentId;
    private Integer chunkIndex;
    private Integer charStart;
    private Integer charEnd;
    private Integer pageFrom;
    private String content;
    private OffsetDateTime createdAt;
}
