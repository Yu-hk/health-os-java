package com.kangheng.healthos.worker.patient;

import java.util.Map;

import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.spi.outbox.OutboxEventHandler;
import com.kangheng.healthos.model.outbox.OutboxEventEntity;
import com.kangheng.healthos.service.patient.PatientDeletionService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 患者删除任务的执行器。
 *
 * <p>失败时直接抛出让 outbox 重试：任务本身幂等（已删的跳过、台账已归档不重写），
 * 重试是安全的；而"吞掉异常标完成"会让档案永远删不干净。
 */
@Component
public class PatientDeletionHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PatientDeletionHandler.class);

    private final PatientDeletionService deletionService;

    public PatientDeletionHandler(PatientDeletionService deletionService) {
        this.deletionService = deletionService;
    }

    @Override
    public String eventType() {
        return PatientDeletionService.EVENT_ERASE;
    }

    @Override
    public void handle(OutboxEventEntity event) {
        Map<String, Object> payload = Json.readMap(event.getPayload());
        Object taskId = payload.get("taskId");
        if (taskId == null) {
            throw new IllegalStateException("事件载荷缺少 taskId: " + event.getPayload());
        }
        log.info("开始执行患者删除 eventId={} taskId={}", event.getId(), taskId);
        deletionService.process(String.valueOf(taskId));
    }
}
