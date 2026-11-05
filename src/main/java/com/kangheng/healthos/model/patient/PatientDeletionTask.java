package com.kangheng.healthos.model.patient;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 删除任务。两段式：先 PREVIEW（列出将被删除的资源，给一次性令牌），
 * 再 CONFIRM 才真正执行——医疗数据不能一个 DELETE 就没了。
 */
@Data
@TableName("patient_deletion_task")
public class PatientDeletionTask {

    @TableId(type = IdType.INPUT)
    private String id;
    private String patientId;
    /** 归属人：发起删除的人。只有他能确认这次删除、看这次删除的台账。 */
    private String owner;
    /** PREVIEW / PENDING / RUNNING / COMPLETED / FAILED */
    private String status;
    private String requestedBy;
    private String reason;
    private String previewToken;
    private Integer resourceCount;
    /** 台账归档后的对象键与哈希，用于事后校验"台账没被改过"。 */
    private String ledgerKey;
    private String ledgerSha256;
    private String errorMessage;
    private OffsetDateTime createdAt;
    private OffsetDateTime confirmedAt;
    private OffsetDateTime completedAt;
}
