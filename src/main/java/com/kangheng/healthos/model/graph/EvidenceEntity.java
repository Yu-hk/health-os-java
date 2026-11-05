package com.kangheng.healthos.model.graph;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 证据：一段原文引用及其精确字符区间，问答引用与图谱溯源都指向它。 */
@Data
@TableName("evidence")
public class EvidenceEntity {

    @TableId(type = IdType.INPUT)
    private String id;
    private String documentId;
    private String artifactId;
    private String quote;
    private Integer charStart;
    private Integer charEnd;
    private Integer pageFrom;
    private Integer pageTo;
    private BigDecimal confidence;
    private OffsetDateTime createdAt;
}
