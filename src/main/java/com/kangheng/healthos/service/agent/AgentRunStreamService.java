package com.kangheng.healthos.service.agent;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import com.kangheng.healthos.contract.agent.AgentRunContract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent Run 事件流（SSE）。
 *
 * <p><b>SSE 只是轮询的流式包装</b>：底层读的还是 {@code GET /events?after_seq=}
 * 同一份事件表与同一个 seq，不是第二套状态机。这样断线重连只需带上最后收到的
 * {@code id}（就是 seq），补发语义和轮询完全一致。
 *
 * <p>SSE 泵是纯 IO 等待，复用问答流的线程池，避免再开一组常驻线程。
 */
@Service
public class AgentRunStreamService {

    private static final Logger log = LoggerFactory.getLogger(AgentRunStreamService.class);

    private static final int BATCH = 50;
    private static final long POLL_INTERVAL_MS = 300;
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final AgentRunEventService eventService;
    private final ExecutorService executor;
    private final Map<String, Set<SseEmitter>> connections = new ConcurrentHashMap<>();

    public AgentRunStreamService(AgentRunEventService eventService,
                                 @Qualifier("qaStreamExecutor") ExecutorService executor) {
        this.eventService = eventService;
        this.executor = executor;
    }

    /** 订阅。先鉴权再建连接，越权不该拿到一个「连上了但永远没数据」的连接。 */
    public SseEmitter subscribe(String username, String runId, int afterSeq) {
        eventService.owned(username, runId);

        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        AtomicBoolean closed = new AtomicBoolean(false);
        connections.computeIfAbsent(runId, key -> ConcurrentHashMap.newKeySet()).add(emitter);
        Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) {
                Set<SseEmitter> set = connections.get(runId);
                if (set != null) {
                    set.remove(emitter);
                    if (set.isEmpty()) {
                        connections.remove(runId);
                    }
                }
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        executor.submit(() -> pump(username, runId, Math.max(0, afterSeq), emitter, closed));
        return emitter;
    }

    private void pump(String username, String runId, int afterSeq,
                      SseEmitter emitter, AtomicBoolean closed) {
        int cursor = afterSeq;
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        try {
            while (!closed.get() && System.currentTimeMillis() < deadline) {
                AgentRunContract.EventPage page = eventService.events(username, runId, cursor, BATCH);
                for (AgentRunContract.EventView event : page.items()) {
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(event.seq()))
                            .name(event.type())
                            .data(event, MediaType.APPLICATION_JSON));
                    cursor = event.seq();
                }
                if (isTerminal(page.status())) {
                    // 终态：补完最后一批就关，不留悬挂连接。
                    emitter.complete();
                    return;
                }
                if (page.items().isEmpty()) {
                    Thread.sleep(POLL_INTERVAL_MS);
                }
            }
            if (!closed.get()) {
                emitter.complete();
            }
        } catch (IOException ex) {
            log.debug("协同事件流断开 runId={} reason={}", runId, ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ex) {
            log.warn("协同事件流异常 runId={}: {}", runId, ex.toString());
            try {
                emitter.completeWithError(ex);
            } catch (RuntimeException ignored) {
                // 连接已经断了，补报错误没有意义。
            }
        }
    }

    private static boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status)
                || "CANCELLED".equals(status);
    }

    public int activeConnections() {
        return connections.values().stream().mapToInt(Set::size).sum();
    }
}
