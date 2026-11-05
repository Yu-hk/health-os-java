package com.kangheng.healthos.model.care;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 一份健康管理文档。报告（report）与四周计划（plan）共用这张表，用 {@code kind} 区分。
 *
 * <p>对应原工程 {@code care_documents}。
 *
 * <p>状态机：
 * <pre>
 * DRAFT ──sign──▶ SIGNED ──confirm──▶ ACTIVE
 *   ▲                 │
 *   └──revise（生成新草稿，parent 指向上一版）──┘
 * </pre>
 *
 * <p>关键约束：<b>报告必须绑定来源体检的不可变快照</b>（{@code sourceSnapshot}）。
 * 体检数据后续被修订不影响已生成报告——否则"这份报告当时依据什么"就说不清了，
 * 这是医疗场景里必须能回溯的问题。
 */
@Data
@TableName("care_document")
public class CareDocument {

    @TableId(type = IdType.INPUT)
    private String id;
    /** 归属人。健康管理文档是私人的，查询必须带 owner。 */
    private String owner;
    /** report（健康评估报告）/ plan（四周执行计划）。 */
    private String kind;
    private String patientId;
    /** 来源体检 ID 与体检修订号。 */
    private String sourceExamId;
    private Integer sourceExamRevision;
    /** 来源报告 ID 与报告修订号（计划才有）。 */
    private String sourceReportId;
    private Integer sourceReportRevision;
    /** 修订链：指向上一版文档。 */
    private String parentDocumentId;
    /** 幂等键：(owner, kind, idempotencyKey) 唯一。 */
    private String idempotencyKey;
    /** 输入指纹：同一幂等键换了输入要报错，不能静默复用。 */
    private String inputFingerprint;
    private Integer revision;
    /** DRAFT / SIGNED / ACTIVE / SUPERSEDED。 */
    private String status;
    /** 分节内容 JSON：{sectionKey: {text, metricRefs}}。 */
    private String sections;
    /** 汇总 JSON：完整度、任务清单、打卡进度。 */
    private String aggregate;
    /** 来源体检的不可变快照 JSON。 */
    private String sourceSnapshot;
    /** 计划起始日期（ISO yyyy-MM-dd）。 */
    private String startDate;
    /** 签发时的修订号。 */
    private Integer signedRevision;
    /** 当前生效的修订号。 */
    private Integer activeRevision;
    private String jobId;
    private String errorCode;
    private String errorMessage;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
