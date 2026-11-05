package com.kangheng.healthos.provider.tracing;

import com.kangheng.healthos.spi.tracing.TraceWriter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单写线程的异步写入器。
 *
 * <p>队列有界：满了就丢并计数，绝不反压业务线程。观测系统把自己变成
 * 故障源是最糟糕的设计——数据库慢的时候，业务应该照常跑完，最多丢几条轨迹。
 */
public final class AsyncTraceWriter implements TraceWriter {

    private static final Logger log = LoggerFactory.getLogger(AsyncTraceWriter.class);

    private static final long FLUSH_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);

    private record Entry(String kind, Runnable action) {
    }

    private final BlockingQueue<Entry> queue;
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong inFlight = new AtomicLong();
    private final Thread worker;
    private volatile boolean running = true;

    public AsyncTraceWriter(int capacity) {
        this.queue = new LinkedBlockingQueue<>(Math.max(64, capacity));
        this.worker = new Thread(this::loop, "trace-writer");
        this.worker.setDaemon(true);
        this.worker.start();
        log.info("轨迹写入器启动：异步模式，队列容量 {}", queue.remainingCapacity());
    }

    @Override
    public void submit(String kind, Runnable action) {
        if (!running) {
            dropped.incrementAndGet();
            return;
        }
        submitted.incrementAndGet();
        inFlight.incrementAndGet();
        if (!queue.offer(new Entry(kind, action))) {
            inFlight.decrementAndGet();
            dropped.incrementAndGet();
        }
    }

    @Override
    public void flush() {
        long deadline = System.nanoTime() + FLUSH_TIMEOUT_NANOS;
        while (System.nanoTime() < deadline) {
            if (queue.isEmpty() && inFlight.get() == 0) {
                return;
            }
            LockSupport.parkNanos(2_000_000L);
        }
        log.warn("轨迹写入器 flush 超时：队列剩余 {}，未完成 {}", queue.size(), inFlight.get());
    }

    @Override
    public Stats stats() {
        return new Stats("async", submitted.get(), written.get(), dropped.get(), queue.size());
    }

    @Override
    public void close() {
        running = false;
        flush();
        worker.interrupt();
    }

    private void loop() {
        while (running || !queue.isEmpty()) {
            Entry entry;
            try {
                entry = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                if (!running) {
                    return;
                }
                continue;
            }
            if (entry == null) {
                continue;
            }
            runSafely(entry);
        }
    }

    private void runSafely(Entry entry) {
        try {
            entry.action().run();
            written.incrementAndGet();
        } catch (Throwable ex) {
            // 写失败只降级为丢弃：观测存储出问题不该让业务线程或进程退出。
            dropped.incrementAndGet();
            log.debug("轨迹写入失败 kind={}", entry.kind(), ex);
        } finally {
            inFlight.decrementAndGet();
        }
    }
}
