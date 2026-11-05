package com.kangheng.healthos.model.qa;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 单路 RAG 诊断运行（对齐原工程 {@code qa_diagnostics.py}）。
 *
 * <p>用途：针对某个患者知识库绑定，单独跑一次"检索这一路"，把命中/未命中/依赖不可用
 * 的结果如实记下来，用来回答"这个库到底检索不检索得到东西、答案有没有出处"，
 * 而不是再生成一遍综合回答。
 *
 * <p><b>为什么 status 是唯一真相来源、result 只是 JSON 列</b>：worker 是异步执行的，
 * 客户端轮询时先看 status；result / draft 只是终态的补充。把状态拆成结构化列，
 * 才能用索引查"哪些还卡在 QUEUED"，不必反序列化每条 JSON。
 *
 * <p><b>失败仍保留 draft</b>：检索片段是已经拿到的"已完成结果"，即使后续序列化出错，
 * 也不该把它抹掉——原工程明确要求已完成结果保留。
 *
 * <p>status 取值：QUEUED / RUNNING / SUCCEEDED / FAILED。
 */
@Data
@TableName("qa_diagnostic")
public class QaDiagnostic {

    @TableId(type = IdType.INPUT)
    private String id;
    /** 归属人。越权查询一律 404。 */
    private String owner;
    private String patientId;
    private String bindingId;
    /** 关联会话（取该患者最近一次问答运行的会话），供会话维度列表查询。 */
    private String conversationId;
    private String question;
    /** UNIQUE：ragflow_diagnostic|bindingId|question|expectedVersion|idempotencyKey 的 sha256。 */
    private String operationKey;
    private String status;
    /** 调用方提交的绑定版本，仅留痕。 */
    private Integer expectedVersion;
    /** TEXT JSON：执行结果 Map + 内部 evidence 数组（详情里拆开呈现）。 */
    private String result;
    /** TEXT：检索片段摘要；失败时保留。 */
    private String draft;
    private String errorCode;
    private String errorMessage;
    private OffsetDateTime createdAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
}
