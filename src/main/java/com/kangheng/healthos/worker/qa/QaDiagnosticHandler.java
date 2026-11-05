package com.kangheng.healthos.worker.qa;

import java.util.Map;

import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.model.outbox.OutboxEventEntity;
import com.kangheng.healthos.service.qa.QaDiagnosticService;
import com.kangheng.healthos.spi.outbox.OutboxEventHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 单路 RAG 诊断的执行器：只从 payload 里取 runId，逻辑都在服务里。
 *
 * <p>与问答运行共用同一套 Outbox 机制：投递与业务单据同事务，进程重启后事件还在。
 * 这里刻意不 catch 异常——{@code QaDiagnosticService.execute} 内部已经把检索故障
 * 全部降级成落库结果，能抛到这里的是"载荷缺 runId"这种数据错误，应当让调度器
 * 按重试策略留痕，而不是悄悄当成成功。
 */
@Component
public class QaDiagnosticHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(QaDiagnosticHandler.class);

    private final QaDiagnosticService diagnosticService;

    public QaDiagnosticHandler(QaDiagnosticService diagnosticService) {
        this.diagnosticService = diagnosticService;
    }

    @Override
    public String eventType() {
        return QaDiagnosticService.EVENT_EXECUTE;
    }

    @Override
    public void handle(OutboxEventEntity event) {
        Map<String, Object> payload = Json.readMap(event.getPayload());
        Object runId = payload == null ? null : payload.get("runId");
        if (runId == null) {
            throw new IllegalStateException("事件载荷缺少 runId: " + event.getPayload());
        }
        log.info("开始执行单路 RAG 诊断 eventId={} runId={}", event.getId(), runId);
        diagnosticService.execute(String.valueOf(runId));
    }
}
