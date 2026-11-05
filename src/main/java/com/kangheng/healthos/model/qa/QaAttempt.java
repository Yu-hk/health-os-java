package com.kangheng.healthos.model.qa;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 节点的一次尝试（对齐原项目 {@code qa_attempts}）。
 *
 * <p>存在的意义是"过程可查"：一个节点失败后重试了三次，
 * 前两次为什么失败、第三次凭什么成功，都在这里留痕，不靠日志考古。
 */
@Data
@TableName("qa_attempt")
public class QaAttempt {

    @TableId(type = IdType.INPUT)
    private String id;
    private String runId;
    private String nodeKey;
    /** 从 1 开始，同一节点内递增。 */
    private Integer attemptNo;
    /** RUNNING / SUCCEEDED / FAILED。 */
    private String status;
    private String inputHash;
    /** JSON：本次尝试的附加信息（耗时、命中条数等）。 */
    private String detail;
    private String errorCode;
    private String errorMessage;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
}
