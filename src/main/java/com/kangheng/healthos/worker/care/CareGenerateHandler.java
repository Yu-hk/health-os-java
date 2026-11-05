package com.kangheng.healthos.worker.care;

import java.util.Map;

import com.kangheng.healthos.service.care.CareService;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.spi.outbox.OutboxEventHandler;
import com.kangheng.healthos.model.outbox.OutboxEventEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 文档分节生成的执行器：只负责取 payload 里的 documentId，执行逻辑都在服务里。 */
@Component
public class CareGenerateHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(CareGenerateHandler.class);

    private final CareService careService;

    public CareGenerateHandler(CareService careService) {
        this.careService = careService;
    }

    @Override
    public String eventType() {
        return CareService.EVENT_GENERATE;
    }

    @Override
    public void handle(OutboxEventEntity event) {
        Map<String, Object> payload = Json.readMap(event.getPayload());
        Object documentId = payload == null ? null : payload.get("documentId");
        if (documentId == null) {
            throw new IllegalStateException("事件载荷缺少 documentId: " + event.getPayload());
        }
        log.info("开始执行文档分节生成 eventId={} documentId={}", event.getId(), documentId);
        careService.generate(String.valueOf(documentId));
    }
}
