package com.kangheng.healthos.model.qa;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 运行内部的节点（对齐原项目 {@code qa_run_nodes}）。
 *
 * <p>节点是<b>断点续跑的抓手</b>：执行时逐节点判断，已 {@code SUCCEEDED} 的直接跳过，
 * 只有 {@code PENDING / FAILED} 的会重跑。因此"检索成功但模型超时"这种半途失败，
 * 重试时不用再打一次知识库。
 *
 * <p>status：PENDING / RUNNING / SUCCEEDED / FAILED / SKIPPED / CANCELLED。
 */
@Data
@TableName("qa_run_node")
public class QaRunNode {

    @TableId(type = IdType.INPUT)
    private String id;
    private String runId;
    /** 节点键：retrieval / 各分支 key / summary。 */
    private String nodeKey;
    private String label;
    private Integer orderIndex;
    private String status;
    private Integer attemptCount;
    /** 自动重试上限。超过后不再自动重试，等人工按节点重试。 */
    private Integer autoAttemptLimit;
    /** JSON：节点产出的结果（如某分支的答案与引用）。 */
    private String result;
    private String errorCode;
    private String errorMessage;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private OffsetDateTime updatedAt;
}
