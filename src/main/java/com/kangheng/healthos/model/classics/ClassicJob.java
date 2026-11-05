package com.kangheng.healthos.model.classics;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 一次流水线执行。status: PENDING / RUNNING / SUCCEEDED / FAILED。 */
@Data
@TableName("classic_job")
public class ClassicJob {

    @TableId(type = IdType.INPUT)
    private String id;
    private String documentId;
    private String status;
    private Integer attempt;
    /** 模型与连接方式快照，保证重跑时能复现当时的配置。 */
    private String providerSnapshot;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private String errorCode;
    private String errorMessage;
    private OffsetDateTime createdAt;
}
