package com.kangheng.healthos.model.care;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 审计流水：谁在什么时候对哪份文档做了什么。
 *
 * <p>对应原工程 {@code care_audits}。与修订快照的区别：快照存"内容"，
 * 审计存"动作"，两者互不替代。
 */
@Data
@TableName("care_audit")
public class CareAudit {

    @TableId(type = IdType.INPUT)
    private String id;
    private String documentId;
    private Integer revision;
    private String actor;
    /** section_edited / report_signed / plan_confirmed / task_completed / task_unchecked … */
    private String action;
    private String reason;
    /** 被改动的分节 key；整体动作时为空。 */
    private String changedSection;
    private OffsetDateTime createdAt;
}
