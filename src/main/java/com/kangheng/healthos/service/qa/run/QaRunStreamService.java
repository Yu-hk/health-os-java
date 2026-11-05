package com.kangheng.healthos.service.qa.run;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.kangheng.healthos.contract.qa.QaRunContract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 问答运行的事件流（SSE）。
 *
 * <p><b>为什么用 SSE 而不是 WebSocket</b>：这里的数据流是单向的（服务端 → 客户端），
 * SSE 走普通 HTTP，浏览器原生支持、断线自动重连、反向代理友好，
 * 而 WebSocket 需要额外的心跳与协议升级。
 *
 * <p><b>断点续传</b>：客户端带 {@code afterSeq} 连上来，服务端只补之后的事件。
 * 事件 seq 在同一个 run 内连续递增，所以"补齐"不会漏也不会重。
 *
 * <p><b>终态即关</b>：run 到达终态（完成/证据不足/取消/失败）后主动关闭连接，
 * 不让连接一直挂着占线程。
 */
@Service
public class QaRunStreamService {

    private static final Logger log = LoggerFactory.getLogger(QaRunStreamService.class);

    /** 单次推送的最大事件数，防止一次把几千条事件塞进一个 SSE 帧。 */
    private static final int BATCH = 50;

    /** 轮询间隔。事件写入是有间隔的（节点粒度），不需要压到毫秒级。 */
    private static final long POLL_INTERVAL_MS = 300;

    /** SSE 连接最长存活时间。超过就断开，让客户端重连。 */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final QaRunService runService;
    private final ExecutorService executor;

    /** 活跃连接：runId → 连接集合。断开时要能立刻从队列里移除。 */
    private final Map<String, java.util.Set<SseEmitter>> connections = new ConcurrentHashMap<>();

    public QaRunStreamService(QaRunService runService,
                              @Qualifier("qaStreamExecutor") ExecutorService executor) {
        this.runService = runService;
        this.executor = executor;
    }

    /**
     * 订阅某个运行的事件流。
     *
     * @param afterSeq 从这个序号之后开始补；0 表示从头
     */
    public SseEmitter subscribe(String username, String runId, int afterSeq) {
        // 先做一次鉴权与存在性校验：越权不该拿到一个"连上了但没数据"的连接
        runService.detail(username, runId);

        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        AtomicBoolean closed = new AtomicBoolean(false);
        connections.computeIfAbsent(runId, key -> ConcurrentHashMap.newKeySet()).add(emitter);

        Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) {
                java.util.Set<SseEmitter> set = connections.get(runId);
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

        executor.submit(() -> pump(username, runId, afterSeq, emitter, closed));
        return emitter;
    }

    /** 推送循环：按 seq 增量拉取并推送，直到终态或连接断开。 */
    private void pump(String username, String runId, int afterSeq,
                      SseEmitter emitter, AtomicBoolean closed) {
        int cursor = afterSeq;
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        try {
            while (!closed.get() && System.currentTimeMillis() < deadline) {
                QaRunContract.EventPage page = runService.events(username, runId, cursor, BATCH);
                for (QaRunContract.EventView event : page.items()) {
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(event.seq()))
                            .name(event.type())
                            .data(event, org.springframework.http.MediaType.APPLICATION_JSON));
                    cursor = event.seq();
                }
                if (isTerminal(page.status())) {
                    // 终态：补完最后一批就断开，不留悬挂连接
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
            // 客户端断开是常态，不是错误
            log.debug("问答事件流断开 runId={} reason={}", runId, ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ex) {
            log.warn("问答事件流异常 runId={}: {}", runId, ex.toString());
            try {
                emitter.completeWithError(ex);
            } catch (RuntimeException ignored) {
                // 连接已经断了，补报错误也没意义
            }
        }
    }

    private static boolean isTerminal(String status) {
        return QaRunService.STATUS_SUCCEEDED.equals(status)
                || QaRunService.STATUS_INSUFFICIENT.equals(status)
                || QaRunService.STATUS_CANCELLED.equals(status)
                || QaRunService.STATUS_FAILED.equals(status);
    }

    /** 当前活跃连接数，供健康检查观察。 */
    public int activeConnections() {
        return connections.values().stream().mapToInt(java.util.Set::size).sum();
    }
}
