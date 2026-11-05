package com.kangheng.healthos.model.patient;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 患者附件。objectKey 全局唯一，删除走软删 + 对象真删两步。 */
@Data
@TableName("patient_object")
public class PatientObjectEntity {

    @TableId(type = IdType.INPUT)
    private String id;
    private String patientId;
    /** 归属人，随患者档案继承。 */
    private String owner;
    /** REPORT / IMAGE / OTHER */
    private String kind;
    private String objectKey;
    private String filename;
    private String contentType;
    private Long sizeBytes;
    private String sha256;
    /** ACTIVE / DELETED */
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime deletedAt;
}
