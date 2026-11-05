package com.kangheng.healthos.service.tracing;

import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TraceContextTest {

    @AfterEach
    void cleanup() {
        TraceContext.clear();
    }

    private static TraceContext.Frame frame(String traceId, String spanId) {
        return new TraceContext.Frame(traceId, spanId, "admin", "pat_1", "qa_ask", "doc_1",
                OffsetDateTime.now());
    }

    @Test
    void restoresPreviousFrameAfterNestedCall() {
        TraceContext.set(frame("t1", "root"));

        TraceContext.call(frame("t1", "child"), () -> {
            assertEquals("child", TraceContext.current().spanId());
            return null;
        });

        assertEquals("root", TraceContext.current().spanId(), "退出后必须回到外层 span");
        assertEquals("t1", TraceContext.current().traceId());
    }

    @Test
    void callWithoutPreviousContextClearsAfterwards() {
        TraceContext.call(frame("t2", "s1"), () -> {
            assertEquals("s1", TraceContext.current().spanId());
            return null;
        });
        assertNull(TraceContext.current(), "进入前没有上下文，退出后也不该留下任何东西");
    }

    @Test
    void withSpanKeepsTraceAndMetadata() {
        TraceContext.Frame origin = frame("t3", "p1");
        TraceContext.Frame child = origin.withSpan("c1");

        assertEquals("t3", child.traceId());
        assertEquals("c1", child.spanId());
        assertEquals("admin", child.owner());
        assertEquals(origin.acceptedAt(), child.acceptedAt(), "换 span 不允许重置开始时间");
    }

    @Test
    void contextDoesNotLeakAcrossThreadsUnlessCarried() throws Exception {
        TraceContext.set(frame("t4", "root"));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TraceContext.Frame snapshot = TraceContext.current();

            assertNull(pool.submit(() -> TraceContext.current()).get(),
                    "线程池线程默认拿不到调用方上下文");

            String carried = pool.submit(() -> TraceContext.call(snapshot,
                    () -> TraceContext.current().traceId())).get();
            assertEquals("t4", carried, "显式搬运后任务里必须能接上轨迹");

            assertNull(pool.submit(() -> TraceContext.current()).get(),
                    "搬运任务结束后必须还原，避免线程复用把上下文漏给下一次请求");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void clearRemovesContext() {
        TraceContext.set(frame("t5", "root"));
        TraceContext.clear();
        assertNull(TraceContext.current());
    }

    @Test
    void concurrentCarriesDoNotInterfere() {
        TraceContext.Frame a = frame("ta", "sa");
        TraceContext.Frame b = frame("tb", "sb");
        String ra = CompletableFuture.supplyAsync(
                () -> TraceContext.call(a, () -> TraceContext.current().traceId())).join();
        String rb = CompletableFuture.supplyAsync(
                () -> TraceContext.call(b, () -> TraceContext.current().traceId())).join();
        assertEquals("ta", ra);
        assertEquals("tb", rb);
    }
}
