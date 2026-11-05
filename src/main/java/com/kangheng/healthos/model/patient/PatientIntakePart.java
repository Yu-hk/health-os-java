package com.kangheng.healthos.model.patient;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 资料录入的一个分片。
 *
 * <p>一份资料拆成若干"段"（主诉/现病史/既往史/用药史/体格检查/检查检验……），
 * 每段单独抽取。<b>每段独立成败</b>：一段抽坏了不该让整份资料作废。
 */
@Data
@TableName("patient_intake_part")
public class PatientIntakePart {

    @TableId(type = IdType.INPUT)
    private String id;
    private String examId;
    private String owner;
    /** 分片键，见 {@code PatientIntakeService.PART_KEYS}。 */
    private String partKey;
    /** 该分片的原文。 */
    private String content;
    /** PENDING / SUCCEEDED / FAILED / SKIPPED */
    private String status;
    /** 抽取结果 JSON。 */
    private String payload;
    private String errorCode;
    private String errorMessage;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
