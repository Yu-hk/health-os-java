package com.kangheng.healthos.worker.patient;

import java.util.Map;

import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.spi.outbox.OutboxEventHandler;
import com.kangheng.healthos.model.outbox.OutboxEventEntity;
import com.kangheng.healthos.service.patient.PatientBatchService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 患者批次导入的执行器：只负责取 payload 里的 batchId，执行逻辑都在服务里。 */
@Component
public class PatientBatchImportHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PatientBatchImportHandler.class);

    private final PatientBatchService batchService;

    public PatientBatchImportHandler(PatientBatchService batchService) {
        this.batchService = batchService;
    }

    @Override
    public String eventType() {
        return PatientBatchService.EVENT_IMPORT;
    }

    @Override
    public void handle(OutboxEventEntity event) {
        Map<String, Object> payload = Json.readMap(event.getPayload());
        Object batchId = payload.get("batchId");
        if (batchId == null) {
            throw new IllegalStateException("事件载荷缺少 batchId: " + event.getPayload());
        }
        log.info("开始执行患者批次导入 eventId={} batchId={}", event.getId(), batchId);
        batchService.process(String.valueOf(batchId));
    }
}
