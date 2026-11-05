package com.kangheng.healthos.model.patient;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 删除任务的一行资源明细：删了哪些、删没删掉，都要能逐条对账。 */
@Data
@TableName("patient_deletion_resource")
public class PatientDeletionResource {

    @TableId(type = IdType.INPUT)
    private String id;
    private String taskId;
    /** 归属人，随删除任务继承。 */
    private String owner;
    /** METRIC / REVISION / OBJECT / BATCH_ROW / QA_MESSAGE */
    private String resourceKind;
    private String resourceKey;
    /** 附加定位信息，例如对象存储的 objectKey。 */
    private String scope;
    private Boolean erased;
    private OffsetDateTime erasedAt;
    private OffsetDateTime createdAt;
}
