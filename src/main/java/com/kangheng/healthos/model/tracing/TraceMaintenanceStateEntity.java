package com.kangheng.healthos.model.tracing;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 保留策略的执行状态。单行（id=daily），用来回答"上次清理是什么时候、删了多少"，
 * 也用来避免重启后同一时间窗重复清理。
 */
@Data
@TableName("trace_maintenance_state")
public class TraceMaintenanceStateEntity {

    @TableId(type = IdType.INPUT)
    private String id;
    private OffsetDateTime lastRunAt;
    private OffsetDateTime nextRunAt;
    private String lastResult;
    private OffsetDateTime updatedAt;
}
