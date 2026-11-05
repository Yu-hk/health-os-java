package com.kangheng.healthos.model.tracing;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 一次操作的耗时与结果。{@code attributes} 是白名单字段的 JSON，
 * 绝不整包转储请求体或异常堆栈——观测数据不该成为第二个泄露面。
 */
@Data
@TableName("trace_span")
public class TraceSpanEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceId;
    private String spanId;
    private String parentSpanId;
    private String name;
    /** business_stage / model / retrieval / graph / ocr / convert / queue */
    private String kind;
    private String service;
    /** OK / FAILED */
    private String status;
    private String errorCode;
    private String errorMessage;
    private String attributes;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private Long durationMs;
}
