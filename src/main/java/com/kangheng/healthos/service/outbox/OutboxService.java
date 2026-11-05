package com.kangheng.healthos.service.outbox;

import java.time.OffsetDateTime;

import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.mapper.outbox.OutboxMapper;
import com.kangheng.healthos.model.outbox.OutboxEventEntity;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 写入。
 *
 * <p>必须在业务事务内调用：事件与业务数据同生共死，避免"单据建好了、任务没派出去"。
 */
@Service
public class OutboxService {

    public static final String STATUS_PENDING = "PENDING";

    private final OutboxMapper outboxMapper;

    public OutboxService(OutboxMapper outboxMapper) {
        this.outboxMapper = outboxMapper;
    }

    @Transactional
    public OutboxEventEntity append(String aggregateType, String aggregateId,
                                    String eventType, Object payload) {
        OffsetDateTime now = OffsetDateTime.now();
        OutboxEventEntity event = new OutboxEventEntity();
        event.setId(Ids.newId("obe"));
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setPayload(payload == null ? null : Json.write(payload));
        event.setStatus(STATUS_PENDING);
        event.setAttempts(0);
        event.setAvailableAt(now);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        outboxMapper.insert(event);
        return event;
    }
}
