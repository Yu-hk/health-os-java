package com.kangheng.healthos.model.tracing;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 一次业务执行的收据。对应原工程 {@code trace_source_bindings}。
 *
 * <p>{@code execution_no} 记录同一业务对象被处理到第几次：重跑不是覆盖，
 * 而是新执行号 + {@code previous_trace_id} 指向上一次，轨迹因此可串成链。
 */
@Data
@TableName("trace_run")
public class TraceRunEntity {

    @TableId(type = IdType.INPUT)
    private String traceId;
    private String rootSpanId;
    private String businessType;
    private String businessId;
    private Integer executionNo;
    private String owner;
    private String patientId;
    private String previousTraceId;
    /**
     * 健康度词表：RUNNING / SUCCEEDED / FAILED / INTERRUPTED。
     *
     * <p>只有这四个词，因为这一列要能被聚合（按状态计数、算失败率）。业务侧的
     * 精细状态（如问答的 OK / INSUFFICIENT_EVIDENCE）不往这里塞，否则
     * {@code GROUP BY status} 出来的是一堆各业务自造的词，既不能比较也不能报警。
     */
    private String status;
    /** 业务侧原生状态，仅用于人工排查时看"到底怎么结束的"，不参与聚合。 */
    private String outcome;
    private String errorCode;
    private String errorMessage;
    private OffsetDateTime acceptedAt;
    private OffsetDateTime completedAt;
    private OffsetDateTime expiresAt;
}
