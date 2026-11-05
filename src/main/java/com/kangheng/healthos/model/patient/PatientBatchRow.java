package com.kangheng.healthos.model.patient;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 批次里的一行。<b>每一行独立成一个事务</b>：某一行手机号格式写错，
 * 不能让另外 499 行跟着回滚——这正是批量导入最容易踩的坑。
 */
@Data
@TableName("patient_batch_row")
public class PatientBatchRow {

    @TableId(type = IdType.INPUT)
    private String id;
    private String batchId;
    /** 归属人，随批次继承。 */
    private String owner;
    private Integer rowNo;
    private String externalRef;
    /** 原始行的 JSON，重试时按原样重放，不依赖重新上传。 */
    private String payload;
    /** PENDING / SUCCEEDED / FAILED / SKIPPED */
    private String status;
    private String patientId;
    private String errorCode;
    private String errorMessage;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
