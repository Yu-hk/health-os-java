package com.kangheng.healthos.model.patient;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 患者档案的历史快照。
 *
 * <p>每次成功变更写一条，{@code (patient_id, revision)} 唯一——
 * 这条唯一约束同时充当并发保护：两个请求算出了同一个 revision，只有一个能写进去。
 */
@Data
@TableName("patient_revision")
public class PatientRevision {

    @TableId(type = IdType.INPUT)
    private String id;
    private String patientId;
    /** 归属人，随患者档案继承。 */
    private String owner;
    private Integer revision;
    private String actor;
    private String reason;
    /** 变更后的完整档案 JSON（不含指标）。 */
    private String snapshot;
    private String sha256;
    private OffsetDateTime createdAt;
}
