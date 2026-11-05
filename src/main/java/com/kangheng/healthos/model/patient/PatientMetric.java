package com.kangheng.healthos.model.patient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 体检指标。status: LOW / NORMAL / HIGH / UNKNOWN，由 MetricEvaluator 计算。 */
@Data
@TableName("patient_metric")
public class PatientMetric {

    @TableId(type = IdType.INPUT)
    private String id;
    private String patientId;
    /** 归属人。查询必须带上它，否则就是越权读。 */
    private String owner;
    private String code;
    private String name;
    private BigDecimal value;
    private String unit;
    private BigDecimal referenceLow;
    private BigDecimal referenceHigh;
    private String status;
    private LocalDate measuredAt;
    private OffsetDateTime createdAt;
}
