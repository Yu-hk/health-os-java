package com.kangheng.healthos.model.qa;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 运行事件（对齐原项目 {@code qa_events}）。
 *
 * <p>{@code seq} 在同一个 run 内单调递增且连续，是 SSE 断点续传的唯一依据：
 * 客户端记住最后一个 seq，重连时带 {@code afterSeq} 回来，服务端只补之后的。
 * 事件内容刻意只放"发生了什么"，不放长文本——答案走 run.answer 与节点 result。
 */
@Data
@TableName("qa_event")
public class QaEvent {

    @TableId(type = IdType.INPUT)
    private String id;
    private String runId;
    private Integer seq;
    private String type;
    /** JSON：事件负载。 */
    private String payload;
    private OffsetDateTime createdAt;
}
