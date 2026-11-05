package com.kangheng.healthos.worker.classics;

import java.util.Map;

import com.kangheng.healthos.service.classics.ClassicDocumentService;
import com.kangheng.healthos.service.classics.PipelineOrchestrator;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.spi.outbox.OutboxEventHandler;
import com.kangheng.healthos.model.outbox.OutboxEventEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 典籍接入事件的执行器。
 *
 * <p>这是 Outbox 与流水线之间的唯一连接点：调度器只负责认领与回写状态，
 * 真正的业务执行在编排器里。换成消息队列时，只需把本类挂到消费者上。
 */
@Component
public class ClassicIngestHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ClassicIngestHandler.class);

    private final PipelineOrchestrator orchestrator;

    public ClassicIngestHandler(PipelineOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public String eventType() {
        return ClassicDocumentService.EVENT_INGEST;
    }

    @Override
    public void handle(OutboxEventEntity event) {
        Map<String, Object> payload = Json.readMap(event.getPayload());
        Object documentId = payload.get("documentId");
        Object jobId = payload.get("jobId");
        if (documentId == null || jobId == null) {
            throw new IllegalStateException("事件载荷缺少 documentId/jobId: " + event.getPayload());
        }
        log.info("开始执行典籍接入事件 eventId={} documentId={}", event.getId(), documentId);
        orchestrator.run(String.valueOf(documentId), String.valueOf(jobId));
    }
}
