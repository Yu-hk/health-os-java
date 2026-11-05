package com.kangheng.healthos.model.outbox;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 事务性 Outbox 事件。
 *
 * <p>与业务写在同一事务里落库，再由调度器认领执行——避免"业务已提交、消息没发出"的丢任务问题。
 * status: PENDING / RUNNING / DONE / FAILED。
 */
@Data
@TableName("outbox_event")
public class OutboxEventEntity {

    @TableId(type = IdType.INPUT)
    private String id;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private String payload;
    private String status;
    private Integer attempts;
    private OffsetDateTime availableAt;
    private String lastError;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
