package com.kangheng.healthos.model.patient;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 记忆发布的异步任务。
 *
 * <p>状态：{@code QUEUED → RUNNING → SUCCEEDED / FAILED / CANCELLED}。
 * 失败**保留记录并写 error**，不删任务——"刷新没成功"是需要被看见的事实。
 *
 * <p>本工程没有原工程那套租约 / fencing token / 心跳：写操作统一在 patient 行上
 * 加 {@code FOR UPDATE} 串行。这在单库单实例下够用，但不能跨进程接管在途任务，
 * 代价明确写在 README §9，不假装等价。
 */
@Data
@TableName("patient_memory_job")
public class PatientMemoryJob {

    @TableId(type = IdType.INPUT)
    private String id;
    private String owner;
    private String patientId;
    private String operationId;
    private Integer requestSequence;
    /** 请求体指纹：同一个 operationId 但内容变了要报冲突，不能静默复用旧任务。 */
    private String requestHash;
    /** {@code explicit_refresh} / {@code followup_refresh}。 */
    private String kind;
    private String feedbackId;
    /** 提交时看到的当前版本；执行时对不上说明期间有人刷新过，拒绝静默覆盖。 */
    private String expectedMemoryId;
    private String status;
    private Integer attemptCount;
    private String resultMemoryId;
    private String error;
    private OffsetDateTime createdAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
}
