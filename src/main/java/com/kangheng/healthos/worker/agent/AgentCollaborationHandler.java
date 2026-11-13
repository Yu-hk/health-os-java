package com.kangheng.healthos.worker.agent;

import java.util.Map;

import com.kangheng.healthos.service.agent.AgentCollaborationService;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.spi.outbox.OutboxEventHandler;
import com.kangheng.healthos.model.outbox.OutboxEventEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 协同运行的执行器：只负责取 payload 里的 runId，执行逻辑都在服务里。
 */
@Component
public class AgentCollaborationHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentCollaborationHandler.class);

    private final AgentCollaborationService collaborationService;

    public AgentCollaborationHandler(AgentCollaborationService collaborationService) {
        this.collaborationService = collaborationService;
    }

    @Override
    public String eventType() {
        return AgentCollaborationService.EVENT_EXECUTE;
    }

    @Override
    public void handle(OutboxEventEntity event) {
        Map<String, Object> payload = Json.readMap(event.getPayload());
        Object runId = payload == null ? null : payload.get("runId");
        if (runId == null) {
            throw new IllegalStateException("事件载荷缺少 runId: " + event.getPayload());
        }
        log.info("开始执行多智能体协同 eventId={} runId={}", event.getId(), runId);
        collaborationService.execute(String.valueOf(runId));
    }
}
