package com.kangheng.healthos.worker.patient;

import java.util.Map;

import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.spi.outbox.OutboxEventHandler;
import com.kangheng.healthos.model.outbox.OutboxEventEntity;
import com.kangheng.healthos.service.patient.PatientIntakeService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 资料录入的执行器：只负责取 payload 里的 examId，执行逻辑都在服务里。 */
@Component
public class PatientIntakeHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PatientIntakeHandler.class);

    private final PatientIntakeService intakeService;

    public PatientIntakeHandler(PatientIntakeService intakeService) {
        this.intakeService = intakeService;
    }

    @Override
    public String eventType() {
        return PatientIntakeService.EVENT_EXTRACT;
    }

    @Override
    public void handle(OutboxEventEntity event) {
        Map<String, Object> payload = Json.readMap(event.getPayload());
        Object examId = payload == null ? null : payload.get("examId");
        if (examId == null) {
            throw new IllegalStateException("事件载荷缺少 examId: " + event.getPayload());
        }
        log.info("开始执行资料录入抽取 eventId={} examId={}", event.getId(), examId);
        intakeService.process(String.valueOf(examId));
    }
}
