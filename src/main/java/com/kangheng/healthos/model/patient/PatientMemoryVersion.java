package com.kangheng.healthos.model.patient;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 患者长效记忆的**一个不可变版本**。
 *
 * <p>刷新永远只新增一行并移动 {@link PatientMemoryState} 指针，绝不原地改历史版本。
 * 理由很实际：记忆是要被引用、被导出、被拿去跟医生昨天看到的内容核对的东西，
 * 一旦历史能改，"当时依据是什么"就再也说不清。
 *
 * <p>三个可核对字段：
 * <ul>
 *   <li>{@code data} —— 采样时的事实快照（JSON 文本）；</li>
 *   <li>{@code sources} —— 来源清单，逐条带 {@code content_hash}；</li>
 *   <li>{@code markdown} —— 渲染结果，{@code contentHash} 就是它的 SHA-256。</li>
 * </ul>
 * 导出时把 {@code contentHash} 放进 {@code X-Content-SHA256}，客户端可自行校验文件没被改。
 */
@Data
@TableName("patient_memory_version")
public class PatientMemoryVersion {

    @TableId(type = IdType.INPUT)
    private String id;
    private String owner;
    private String patientId;
    private Integer version;
    /** 事实结构版本，结构变了要能区分新旧记忆。 */
    private String schemaVersion;
    /** 生成方式：本工程只有 {@code programmatic_facts}（程序化事实快照，无模型生成）。 */
    private String generationMode;
    private String contentHash;
    /** 来源清单（剔除采样时间后）的 SHA-256：两次刷新若来源没变，这个值也不变。 */
    private String sourceHash;
    private String data;
    private String sources;
    private String markdown;
    private OffsetDateTime sampledAt;
    private OffsetDateTime createdAt;
}
