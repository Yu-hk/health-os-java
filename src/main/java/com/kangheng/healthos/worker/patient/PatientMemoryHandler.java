package com.kangheng.healthos.worker.patient;

import java.util.Map;

import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.model.outbox.OutboxEventEntity;
import com.kangheng.healthos.service.patient.PatientMemoryJobService;
import com.kangheng.healthos.spi.outbox.OutboxEventHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 记忆发布执行器：只取 payload 里的 jobId，业务全在服务层。 */
@Component
public class PatientMemoryHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PatientMemoryHandler.class);

    private final PatientMemoryJobService jobService;

    public PatientMemoryHandler(PatientMemoryJobService jobService) {
        this.jobService = jobService;
    }

    @Override
    public String eventType() {
        return PatientMemoryJobService.EVENT_PUBLISH;
    }

    @Override
    public void handle(OutboxEventEntity event) {
        Map<String, Object> payload = Json.readMap(event.getPayload());
        Object jobId = payload == null ? null : payload.get("jobId");
        if (jobId == null) {
            throw new IllegalStateException("事件载荷缺少 jobId: " + event.getPayload());
        }
        log.info("开始执行记忆发布 eventId={} jobId={}", event.getId(), jobId);
        jobService.execute(String.valueOf(jobId));
    }
}
