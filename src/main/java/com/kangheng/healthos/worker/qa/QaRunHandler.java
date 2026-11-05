package com.kangheng.healthos.worker.qa;

import java.util.Map;

import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.spi.outbox.OutboxEventHandler;
import com.kangheng.healthos.model.outbox.OutboxEventEntity;
import com.kangheng.healthos.service.qa.run.QaRunService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 问答运行的执行器：只负责从 payload 里取 runId，逻辑都在服务里。
 *
 * <p>走 Outbox 而不是接口内同步跑：模型调用可能几十秒，放在请求线程里会把
 * 连接池占死；而且 Outbox 的失败重试天然提供了"进程重启后这条任务还在"的保证。
 */
@Component
public class QaRunHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(QaRunHandler.class);

    private final QaRunService runService;

    public QaRunHandler(QaRunService runService) {
        this.runService = runService;
    }

    @Override
    public String eventType() {
        return QaRunService.EVENT_EXECUTE;
    }

    @Override
    public void handle(OutboxEventEntity event) {
        Map<String, Object> payload = Json.readMap(event.getPayload());
        Object runId = payload == null ? null : payload.get("runId");
        if (runId == null) {
            throw new IllegalStateException("事件载荷缺少 runId: " + event.getPayload());
        }
        log.info("开始执行问答运行 eventId={} runId={}", event.getId(), runId);
        runService.execute(String.valueOf(runId));
    }
}
