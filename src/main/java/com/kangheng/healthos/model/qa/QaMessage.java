package com.kangheng.healthos.model.qa;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 问答消息。status: OK / INSUFFICIENT_EVIDENCE。 */
@Data
@TableName("qa_message")
public class QaMessage {

    @TableId(type = IdType.INPUT)
    private String id;
    private String conversationId;
    private String role;
    private String content;
    /** JSON 数组，存引用快照，保证历史消息可回读当时的出处。 */
    private String citations;
    /** JSON 数组，并行模式下各路分支的明细快照；单路模式为 null。 */
    private String branches;
    private String status;
    private OffsetDateTime createdAt;
}
