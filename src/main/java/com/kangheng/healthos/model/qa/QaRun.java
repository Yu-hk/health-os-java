package com.kangheng.healthos.model.qa;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 一次问答运行（对齐原项目 {@code qa_runs}）。
 *
 * <p>status 取值：
 * <ul>
 *   <li>{@code QUEUED}——已受理，等待 worker 领取；</li>
 *   <li>{@code RUNNING}——正在执行；</li>
 *   <li>{@code SUCCEEDED}——全部节点跑完（有依据时带 answer）；</li>
 *   <li>{@code INSUFFICIENT_EVIDENCE}——跑完了，但没有依据，原项目同名语义；</li>
 *   <li>{@code FAILED}——有节点失败，可按节点重试；</li>
 *   <li>{@code CANCELLED}——被取消。</li>
 * </ul>
 *
 * <p>{@code configSnapshot} / {@code contextSnapshot} 是<b>冻结快照</b>：续跑时用提交那一刻的值，
 * 而不是当前值。否则"续跑"出来的答案依据和首次不是同一份，等于换了个问题在答。
 */
@Data
@TableName("qa_run")
public class QaRun {

    @TableId(type = IdType.INPUT)
    private String id;
    private String conversationId;
    private String documentId;
    /** 归属人。查询必须带上它，否则就是越权读。 */
    private String owner;
    /** 幂等键。同一个会话内重复提交同一问题不会产生第二次运行。 */
    private String idempotencyKey;
    /** 输入指纹。幂等键相同但指纹不同 → 冲突，而不是静默复用。 */
    private String inputHash;
    private String question;
    private String mode;
    private String patientId;
    private String status;
    /** 乐观锁版本。取消/重试都要带 expectedVersion。 */
    private Integer version;
    /** 事件序号水位线。SSE 用它判断"还有没有新事件"。 */
    private Integer eventSeq;
    private String answer;
    private String errorCode;
    private String errorMessage;
    /** JSON：提交时冻结的模型/检索参数。 */
    private String configSnapshot;
    /** JSON：提交时冻结的证据上下文（命中片段、图谱、指标）。 */
    private String contextSnapshot;
    private OffsetDateTime deadlineAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private OffsetDateTime updatedAt;
}
