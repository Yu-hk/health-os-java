package com.kangheng.healthos.model.qa;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("qa_conversation")
public class QaConversation {

    @TableId(type = IdType.INPUT)
    private String id;
    private String documentId;
    private String title;
    private String createdBy;
    private OffsetDateTime createdAt;
}
