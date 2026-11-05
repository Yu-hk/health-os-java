package com.kangheng.healthos.spi.outbox;

import com.kangheng.healthos.model.outbox.OutboxEventEntity;

/**
 * Outbox 事件处理器。实现类按 {@link #eventType()} 注册，新增业务只需加一个实现。
 */
public interface OutboxEventHandler {

    String eventType();

    void handle(OutboxEventEntity event) throws Exception;
}
