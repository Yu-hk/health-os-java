package com.kangheng.healthos.model.patient;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 一次批量导入。相同内容（sha256）重复提交会复用同一个批次，避免重复建档。 */
@Data
@TableName("patient_batch")
public class PatientBatch {

    @TableId(type = IdType.INPUT)
    private String id;
    private String filename;
    private String sha256;
    /** CSV / JSON */
    private String format;
    private Integer totalRows;
    private Integer succeededRows;
    private Integer failedRows;
    /** PENDING / RUNNING / SUCCEEDED / PARTIAL / FAILED */
    private String status;
    private String createdBy;
    private String errorMessage;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
