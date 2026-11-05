package com.kangheng.healthos.model.care;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 签发记录。一份文档只能签一次（唯一约束在 document_id 上）。
 *
 * <p>对应原工程 {@code care_signatures}。
 *
 * <p>{@code kind} 固定为 {@code authenticated_identity_confirmation}：
 * 这里的"签发"是<b>登录医生身份确认</b>，不是硬件 CA 电子签名，
 * 法律效力不等价。对外文案必须如实说明，不能含糊。
 */
@Data
@TableName("care_signature")
public class CareSignature {

    @TableId(type = IdType.INPUT)
    private String id;
    private String documentId;
    private String owner;
    /** 实际签发的修订号。 */
    private Integer signedRevision;
    /** 签发时客户端声称的修订号（乐观锁）。 */
    private Integer expectedRevision;
    private String signer;
    /** 审核意见。少于 5 个有效字符不允许签发。 */
    private String reviewComment;
    /** 签发时内容的 SHA-256，用于判断"是不是同一份内容重复提交"。 */
    private String contentHash;
    private String startDate;
    /** 签发报告时同步生成的计划文档 ID。 */
    private String planDocumentId;
    private String kind;
    private OffsetDateTime signedAt;
}
