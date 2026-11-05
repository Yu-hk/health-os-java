package com.kangheng.healthos.model.care;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 修订快照：每次改稿留一份当时的完整内容。
 *
 * <p>对应原工程 {@code care_revisions}。有了它才能回答"上一版报告写了什么"，
 * 以及"这份报告被改过几次、每次是谁改的"。
 */
@Data
@TableName("care_revision")
public class CareRevision {

    @TableId(type = IdType.INPUT)
    private String id;
    private String documentId;
    private Integer revision;
    /** 当时的 aggregate JSON。 */
    private String snapshot;
    private String actor;
    private String reason;
    private OffsetDateTime createdAt;
}
