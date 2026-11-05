package com.kangheng.healthos.model.care;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 患者当前生效状态：一个患者同时只有一个生效报告和一个生效计划。
 *
 * <p>对应原工程 {@code care_patient_states}。这张表是"当前生效"的唯一权威——
 * 不用"查最新一条 SIGNED 文档"来推断，因为那会因为时间戳相同而选错。
 */
@Data
@TableName("care_patient_state")
public class CarePatientState {

    @TableId(type = IdType.INPUT)
    private String patientId;
    private String owner;
    private String activeReportId;
    private Integer activeReportRevision;
    private String activePlanId;
    private Integer activePlanRevision;
    private Integer version;
    private OffsetDateTime updatedAt;
}
