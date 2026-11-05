package com.kangheng.healthos.model.qa;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * QA 书签 / 备忘（对齐原工程 {@code qa_memos.py}）。
 *
 * <p><b>为什么单独存一份快照，而不是外键回指消息</b>：消息里的 {@code citations} 是
 * "回答当时"的引用，之后典籍重新解析、切片重排，同一条消息再读可能已经不是同一批依据。
 * 备忘的用途是"把这一刻的结论连同出处钉住"，所以 {@code evidenceSnapshot} /
 * {@code sourceVersions} 都是<b>冻结副本</b>，不随源数据变化。
 *
 * <p><b>只有显式点击才产生</b>：正常问答完成绝不自动写这张表。自动备忘会让"用户真正
 * 认为有价值的结论"和"系统碰巧答过的每一句"混在一起，列表很快变成噪声。
 *
 * <p>{@code sourceMessageId} 唯一：同一条助手消息重复点收藏是幂等的，不新建第二条。
 */
@Data
@TableName("qa_memo")
public class QaMemo {

    @TableId(type = IdType.INPUT)
    private String id;
    /** 归属人。列表/详情都必须带它过滤，越权返回 404。 */
    private String owner;
    private String patientId;
    /** 来源助手消息 ID；UNIQUE，幂等锚点。 */
    private String sourceMessageId;
    /** 该会话最近一次 QaRun.question 的冻结副本。 */
    private String question;
    private String content;
    /** TEXT JSON 数组：从 message.citations 解析出的引用快照。 */
    private String evidenceSnapshot;
    /** TEXT JSON 数组：引用来源的定位（documentId / evidenceId），用于事后核对版本。 */
    private String sourceVersions;
    /** 正文哈希，用于比对"备忘是否被改过"。 */
    private String contentHash;
    private OffsetDateTime createdAt;
}
