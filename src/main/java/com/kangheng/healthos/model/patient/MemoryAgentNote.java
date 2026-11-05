package com.kangheng.healthos.model.patient;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 记忆 Agent 摘录候选（收敛版）。
 *
 * <p>对齐原工程 {@code memory_agent_service.py}：候选先 PENDING，<b>不自动写记忆版本</b>，
 * 发布才落凭据。Java 侧不启 STDIO 子进程、没有独立 worker/模型节点，
 * 因此 jobId / modelNodeId 恒为 null。
 *
 * <p><b>observations 列存的是"来源快照 JSON 文档"</b>，不是裸数组：文档里
 * {@code observations} 成员是 {@code [{entryIndex, quote}]} 数组（满足"observations 为数组、
 * 每项 {entryIndex,quote}"），另有 {@code entries}（随访原文冻结快照）、
 * {@code memoryVersionId}、{@code instruction}、{@code agentNotes[]}。
 * 为什么塞进同一列：规格要求"把 entries 存进 observations 来源快照 JSON 列"、
 * "把 agent_notes 追加到 observations/来源快照 JSON"，又要求 note 在表内自证发布凭据；
 * 独立成文档才能让候选数组、原文快照、发布凭据三者随同一行原子落库。
 * 对外 API 的 {@code observations} 永远只返回那个数组。
 */
@Data
@TableName("memory_agent_note")
public class MemoryAgentNote {

    @TableId(type = IdType.INPUT)
    private String id;
    private String owner;
    private String patientId;
    /** 收敛版不投递 outbox job，恒为 null。 */
    private String jobId;
    /** 复用既有 patient_followup 时回填其 id；没有 followupId 时为 null。 */
    private String feedbackId;
    /** 幂等键，逻辑唯一约束 (owner, patient_id, operation_id)。 */
    private String operationId;
    /** PENDING / PUBLISHED / CANCELLED。 */
    private String status;
    /** 来源快照 JSON 文档（含摘录候选数组），见类注释。 */
    private String observations;
    /** sha256(Json.write(observations 数组))。 */
    private String candidateHash;
    /** sha256(随访原文 + 当前记忆版本 id)。 */
    private String sourceHash;
    /** 收敛版进程内生成候选，无模型节点，恒为 null。 */
    private String modelNodeId;
    /** 发布时写入当前记忆版本 id（发布凭据，不新建版本）。 */
    private String memoryId;
    private OffsetDateTime createdAt;
    private OffsetDateTime publishedAt;
}
