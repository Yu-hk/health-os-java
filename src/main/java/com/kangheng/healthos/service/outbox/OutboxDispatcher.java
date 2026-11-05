package com.kangheng.healthos.service.outbox;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.mapper.outbox.OutboxMapper;
import com.kangheng.healthos.model.outbox.OutboxEventEntity;
import com.kangheng.healthos.service.outbox.OutboxService;
import com.kangheng.healthos.spi.outbox.OutboxEventHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 调度器。
 *
 * <p>周期认领 → 丢到虚拟线程执行 → 按结果回写状态。
 * 认领使用 {@code FOR UPDATE SKIP LOCKED}，多实例部署时无需额外分布式锁。
 * 失败按指数退避重新排期，超过上限置为 FAILED 并保留 last_error 供排查。
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxMapper outboxMapper;
    private final ExecutorService pipelineExecutor;
    private final HealthOsProperties properties;
    private final Map<String, OutboxEventHandler> handlers = new HashMap<>();

    public OutboxDispatcher(OutboxMapper outboxMapper,
                            ExecutorService pipelineExecutor,
                            HealthOsProperties properties,
                            List<OutboxEventHandler> handlerList) {
        this.outboxMapper = outboxMapper;
        this.pipelineExecutor = pipelineExecutor;
        this.properties = properties;
        for (OutboxEventHandler handler : handlerList) {
            OutboxEventHandler previous = handlers.put(handler.eventType(), handler);
            if (previous != null) {
                throw new IllegalStateException("重复注册的 Outbox 事件类型: " + handler.eventType());
            }
        }
        log.info("Outbox 已注册事件类型: {}", handlers.keySet());
    }

    @Scheduled(fixedDelayString = "${healthos.pipeline.dispatcher-interval-ms:2000}")
    public void dispatch() {
        if (!properties.getPipeline().isDispatcherEnabled()) {
            return;
        }
        List<String> claimed;
        try {
            claimed = outboxMapper.claim(properties.getPipeline().getDispatcherBatchSize());
        } catch (Exception ex) {
            // 调度线程必须自己吞掉异常，否则一次数据库抖动会导致整个调度任务被取消
            log.warn("认领 Outbox 事件失败: {}", ex.getMessage());
            return;
        }
        for (String id : claimed) {
            OutboxEventEntity event = outboxMapper.selectById(id);
            if (event == null) {
                continue;
            }
            pipelineExecutor.submit(() -> execute(event));
        }
    }

    void execute(OutboxEventEntity event) {
        OutboxEventHandler handler = handlers.get(event.getEventType());
        if (handler == null) {
            finish(event, "FAILED", "没有注册该事件类型的处理器: " + event.getEventType());
            return;
        }
        try {
            handler.handle(event);
            finish(event, "DONE", null);
        } catch (Exception ex) {
            log.warn("Outbox 事件执行失败 id={} type={} attempts={}",
                    event.getId(), event.getEventType(), event.getAttempts(), ex);
            rescheduleOrFail(event, ex);
        }
    }

    private void rescheduleOrFail(OutboxEventEntity event, Exception ex) {
        int attempts = event.getAttempts() == null ? 1 : event.getAttempts();
        int maxAttempts = properties.getPipeline().getOutboxMaxAttempts();
        if (attempts >= maxAttempts) {
            finish(event, "FAILED", truncate(ex.getMessage()));
            return;
        }
        long delayMillis = Math.min(1000L * (1L << Math.min(attempts, 6)), 60_000L);
        OutboxEventEntity update = new OutboxEventEntity();
        update.setId(event.getId());
        update.setStatus(OutboxService.STATUS_PENDING);
        update.setAvailableAt(OffsetDateTime.now().plusNanos(delayMillis * 1_000_000L));
        update.setLastError(truncate(ex.getMessage()));
        update.setUpdatedAt(OffsetDateTime.now());
        outboxMapper.updateById(update);
    }

    private void finish(OutboxEventEntity event, String status, String error) {
        OutboxEventEntity update = new OutboxEventEntity();
        update.setId(event.getId());
        update.setStatus(status);
        update.setLastError(error);
        update.setUpdatedAt(OffsetDateTime.now());
        outboxMapper.updateById(update);
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 990 ? message : message.substring(0, 990);
    }
}
