package com.kangheng.healthos.model.patient;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 一次资料录入。
 *
 * <p>对应原工程 {@code patient_intake_exams}：一份原始体检/病历资料走完
 * 「上传 → OCR 归一化 → 分片抽取 → 两道人工核查 → 生成正式档案」。
 *
 * <p>状态机：
 * <pre>
 * UPLOADED → EXTRACTING → EXTRACTED → CONFIRMED
 *                          ↘ FAILED
 * </pre>
 * 两道核查（{@code basicReviewStatus} / {@code clinicalReviewStatus}）都必须是
 * {@code CONFIRMED} 才能进 {@code CONFIRMED}——AI 只负责抄，签字的是人。
 */
@Data
@TableName("patient_intake_exam")
public class PatientIntakeExam {

    @TableId(type = IdType.INPUT)
    private String id;
    /** 归属人。录入记录是私人的，查询必须带 owner。 */
    private String owner;
    /** 确认后生成的正式档案 ID；未确认时为空。 */
    private String patientId;
    private String sourceFilename;
    private String sourceObjectKey;
    private String sourceSha256;
    private Long sourceSize;
    private String mediaType;
    /** UPLOADED / EXTRACTING / EXTRACTED / CONFIRMED / FAILED */
    private String status;
    /** PENDING / CONFIRMED / REJECTED */
    private String basicReviewStatus;
    /** PENDING / CONFIRMED / REJECTED */
    private String clinicalReviewStatus;
    private String basicReviewNote;
    private String clinicalReviewNote;
    /** OCR 归一化后的正文。 */
    private String markdown;
    private String markdownSha256;
    /** OCR 元数据 JSON：引擎、页数、是否扫描件。 */
    private String ocrMetadata;
    /** 抽取器名（rule-based / llm:xxx）。 */
    private String extractor;
    /** 抽取汇总 JSON：字段值 + 指标列表。 */
    private String aggregate;
    /** 异常指标码列表 JSON。 */
    private String extractedAbnormalCodes;
    private Integer revision;
    private String jobId;
    private String errorCode;
    private String errorMessage;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
