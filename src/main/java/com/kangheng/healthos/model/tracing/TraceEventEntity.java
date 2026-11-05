package com.kangheng.healthos.model.tracing;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 给不出 span 的零散事实：状态流转、保留策略执行、审核动作等。 */
@Data
@TableName("trace_event")
public class TraceEventEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceId;
    private String spanId;
    private String name;
    private String detail;
    private OffsetDateTime createdAt;
}
