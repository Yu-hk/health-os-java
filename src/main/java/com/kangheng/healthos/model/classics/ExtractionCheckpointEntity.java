package com.kangheng.healthos.model.classics;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 分段抽取检查点。
 *
 * <p>key 由 documentId + 分段内容哈希共同决定，所以只要原文和分段策略不变，
 * 重跑会命中检查点直接复用，不会重复花钱调模型。
 */
@Data
@TableName("extraction_checkpoint")
public class ExtractionCheckpointEntity {

    @TableId(type = IdType.INPUT)
    private String id;
    private String documentId;
    private String jobId;
    private String segmentKey;
    private String payload;
    private OffsetDateTime createdAt;
}
