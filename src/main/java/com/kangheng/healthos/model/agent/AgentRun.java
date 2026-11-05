package com.kangheng.healthos.model.agent;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 一次多智能体协同运行。
 *
 * <p>状态机：
 * <pre>
 * QUEUED ──▶ RUNNING ──▶ SUCCEEDED
 *                 └──▶ FAILED
 * </pre>
 *
 * <p><b>必需角色失败 ⇒ 整轮 FAILED</b>：宁可整轮失败，也不给一份"看起来完整"
 * 其实缺了角色的汇报——缺的那部分若被描述成成功，比明确失败更危险。
 */
@Data
@TableName("agent_run")
public class AgentRun {

    @TableId(type = IdType.INPUT)
    private String id;
    private String owner;
    private String patientId;
    /** progress / execution / statistics。 */
    private String scenario;
    /** 授权范围 JSON 数组。 */
    private String scopes;
    private String instruction;
    /** 幂等键：(owner, patient_id, operation_id) 唯一。 */
    private String operationId;
    /** QUEUED / RUNNING / SUCCEEDED / FAILED / CANCELLED。 */
    private String status;
    /**
     * 每个 run 自己的事件计数器；原子递增，是增量轮询与 SSE 断点续传的依据。
     *
     * <p><b>{@code updateStrategy = NEVER} 不是可选项</b>：execute/finalizeRun 用
     * {@code updateById(run)} 写业务状态时，内存里的 eventSeq 是执行开始时的旧值。
     * 若允许它参与 UPDATE，就会把已经递增到 10 的计数器写回 0，下一次 emit 分配的 seq
     * 与历史事件撞车，(run_id, seq) 唯一约束直接把整轮执行打断。
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer eventSeq;
    /**
     * 控制面乐观锁：cancel / retry 必须带 expectedVersion。
     *
     * <p>同样禁止 updateById 回写：只有 cancel/retry 的显式 update 才能推进版本，
     * 否则业务状态更新会把别人刚推进的版本覆盖回去。
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer version;
    /** 执行截止时间。进程崩溃后由 Outbox 重派时，过期的 run 不重放模型调用。 */
    private OffsetDateTime deadlineAt;
    private String promptVersion;
    private String graphVersion;
    /**
     * 请求指纹：场景 + 授权范围 + 指令的摘要。
     *
     * <p>单独一列，不与 {@link #result} 共用：{@code result} 在执行结束时会被最终结果 JSON 覆盖，
     * 而幂等判定必须在"执行完之后重复提交"时仍然能比对出"这是同一个请求"。
     * 共用一列会让执行后的重复提交被误判成"同一 operationId 换了请求"而报冲突。
     */
    private String requestFingerprint;
    /** 最终结果 JSON：答案 + 各角色状态 + 已核验事实。 */
    private String result;
    private String error;
    private OffsetDateTime createdAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
}
