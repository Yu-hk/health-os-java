package com.kangheng.healthos.model.classics;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 流水线节点。status: PENDING / RUNNING / SUCCEEDED / FAILED / SKIPPED。 */
@Data
@TableName("pipeline_node")
public class PipelineNodeEntity {

    @TableId(type = IdType.INPUT)
    private String id;
    private String documentId;
    private String jobId;
    private String code;
    private String label;
    private Integer orderIndex;
    private String status;
    private BigDecimal progress;
    /** 半结构化细节：artifact_id、segment_count、跳过原因等。 */
    private String detail;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
    private String errorMessage;
}
