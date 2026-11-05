package com.kangheng.healthos.model.agent;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 一次协同运行里的一个可观察事件。
 *
 * <p><b>事件只记「发生了什么」，不记业务正文</b>：正文在 {@code agent_node.output}，
 * 且要看正文必须走带权限与保留期校验的节点详情端点。把正文塞进事件表，
 * 等于给每一个能轮询事件的调用者开了一个绕开权限的口子。
 *
 * <p>{@code seq} 由数据库原子分配（{@code UPDATE ... RETURNING}），
 * 与状态变更在同一事务提交，保证「看到状态翻转」时一定能查到对应事件。
 */
@Data
@TableName("agent_run_event")
public class AgentRunEvent {

    @TableId(type = IdType.INPUT)
    private String id;
    private String runId;
    private Integer seq;
    /** run.queued / run.started / node.completed / ... */
    private String type;
    private String nodeId;
    /** 事件发生时的 run 或 node 状态。 */
    private String status;
    /** 仅元数据（节点 id、kind、name、trace 引用），不含临床正文与凭据。 */
    private String detail;
    private OffsetDateTime createdAt;
}
