package com.kangheng.healthos.model.agent;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 一次 Agent Lab 协议操作（probe / call）的回执。
 *
 * <p>状态机：
 * <pre>
 * running ──▶ succeeded
 *     └────▶ failed
 * </pre>
 *
 * <p><b>为什么 running 要单独成一列、先落库再执行</b>：协议校验是"会留下痕迹"的动作。
 * 如果等全部跑完再一次性写入，进程在 tools/call 中途崩溃就什么都没留下，
 * 前端只会看到一个永远转圈的请求。先提交一条 running，崩溃后视图会把它显示为
 * {@code interrupted}，排查时至少知道"这一步确实开始过"。
 *
 * <p><b>库里永远只存 running/succeeded/failed</b>：interrupted 只是视图层的派生显示，
 * 不回写数据库——把"超时"误写成终态，会让一次其实还在途的操作被当成已结束。
 */
@Data
@TableName("agent_mcp_receipt")
public class AgentMcpReceipt {

    @TableId(type = IdType.INPUT)
    private String id;
    /** 归属账号。目录指标与服务状态都按它隔离，避免跨账号互相看到彼此的探测活动。 */
    private String owner;
    private String patientId;
    /** probe / call。 */
    private String kind;
    /** running / succeeded / failed。 */
    private String status;
    /** 请求元信息 JSON：serviceId、kind、patientId、scopes、tool；不含原始 arguments。 */
    private String request;
    /** 协议明细 JSON：protocolVersion、serverInfo、capabilities、handshakeMs、events、error 等。 */
    private String detail;
    private OffsetDateTime createdAt;
    private OffsetDateTime completedAt;
}
