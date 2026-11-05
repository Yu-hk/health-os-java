package com.kangheng.healthos.model.classics;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 流程产物。kind: SOURCE / MARKDOWN / EXTRACTION / MANIFEST。 */
@Data
@TableName("artifact")
public class ArtifactEntity {

    @TableId(type = IdType.INPUT)
    private String id;
    private String documentId;
    private String jobId;
    private String kind;
    private String objectKey;
    private String sha256;
    private Long sizeBytes;
    private OffsetDateTime createdAt;
}
