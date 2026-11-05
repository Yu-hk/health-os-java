package com.kangheng.healthos.model.patient;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 一次随访反馈。
 *
 * <p>{@code entries} 存的是<b>用户原文</b>（分类 + 文本），不做摘要、不改写、不做模型加工。
 * 原工程把它标成"原始随访记录，非模型事实核验"，这个界限必须保留：
 * 患者自述不能因为被写进记忆就升级成已核验的医学事实。
 *
 * <p>{@code operationId} 由调用方提供，同患者重复提交返回同一条记录（幂等）。
 */
@Data
@TableName("patient_followup")
public class PatientFollowup {

    @TableId(type = IdType.INPUT)
    private String id;
    private String owner;
    private String patientId;
    private String operationId;
    private Integer requestSequence;
    private OffsetDateTime occurredAt;
    private OffsetDateTime createdAt;
    private String actor;
    private String origin;
    private String entries;
}
