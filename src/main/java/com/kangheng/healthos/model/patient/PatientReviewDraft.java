package com.kangheng.healthos.model.patient;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 患者资料录入审阅草稿。
 *
 * <p>对齐原工程 {@code patient_draft_api.py} 的 {@code ReviewDraft}。一条不可让步的语义：
 * <b>草稿绝不改动临床修订与确认状态</b>。本表只存"确认前的暂存区"，与录入单的
 * status / revision / 两道 review 完全解耦——所以这里没有"提交/确认"字段，
 * 正式写入仍走既有的 {@code /exams/{examId}/confirm}。
 *
 * <p>{@code version} 是草稿自己的乐观锁版本；{@code baseRevision} 是这份草稿
 * 依据的录入单 revision，用来检测"期间有人改过核查结论"。
 * 唯一约束 {@code (owner, exam_id, scope)} 保证一个录入单的一个 scope 只有一份草稿。
 */
@Data
@TableName("patient_review_draft")
public class PatientReviewDraft {

    @TableId(type = IdType.INPUT)
    private String id;
    /** 归属人。草稿是私人暂存区，查询必须带 owner。 */
    private String owner;
    private String examId;
    /** basic / clinical。 */
    private String scope;
    /** 生成草稿时录入单的 revision。 */
    private Integer baseRevision;
    /** 草稿自身乐观锁版本，每次成功 PUT +1。 */
    private Integer version;
    /** 白名单字段集合 JSON。 */
    private String payload;
    private OffsetDateTime updatedAt;
}
