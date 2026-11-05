package com.kangheng.healthos.model.agent;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 一次运行里的一个节点：角色产出、校验结论或一次工具读取。
 *
 * <p>{@code outputHash} 是 {@code output} 的 SHA-256。
 * 它是交接完整性的凭据：下游角色只接受<b>同一次运行、状态成功、且摘要对得上</b>的上游结果。
 * 少了这个摘要，"交接"就只是上一轮跑出来的旧结果。
 */
@Data
@TableName("agent_node")
public class AgentNode {

    @TableId(type = IdType.INPUT)
    private String id;
    private String runId;
    /** 角色 id；工具节点为空。 */
    private String role;
    /** role / validation / tool。 */
    private String kind;
    /** 节点名（角色名或工具名）。 */
    private String name;
    /** running / succeeded / failed / skipped。 */
    private String status;
    /** 节点产出 JSON。 */
    private String output;
    /** output 的 SHA-256。 */
    private String outputHash;
    private String summary;
    private String error;
    /** 同一 run 内递增，决定展示顺序。 */
    private Integer ordinal;
    private OffsetDateTime createdAt;
    private OffsetDateTime completedAt;
}
