package com.kangheng.healthos.provider.tracing;

import com.kangheng.healthos.spi.tracing.TraceWriter;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 同步写入器：提交即执行。用于单测与"轨迹必须立刻可查"的小部署。
 *
 * <p>仍然吞掉异常——同步只影响时机，不影响"观测不能反噬业务"这条约束。
 */
public final class DirectTraceWriter implements TraceWriter {

    private static final Logger log = LoggerFactory.getLogger(DirectTraceWriter.class);

    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    @Override
    public void submit(String kind, Runnable action) {
        submitted.incrementAndGet();
        try {
            action.run();
            written.incrementAndGet();
        } catch (RuntimeException ex) {
            dropped.incrementAndGet();
            log.debug("轨迹写入失败 kind={}", kind, ex);
        }
    }

    @Override
    public void flush() {
        // 同步模式下没有待刷内容。
    }

    @Override
    public Stats stats() {
        return new Stats("direct", submitted.get(), written.get(), dropped.get(), 0);
    }
}
