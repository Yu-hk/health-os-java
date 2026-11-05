package com.kangheng.healthos.service.tracing;

import java.util.function.Supplier;

/**
 * 当前线程所处的轨迹位置。
 *
 * <p>只保存四个字段：轨迹、当前 span、归属人、患者。业务代码不需要传递
 * traceId 参数，埋点函数自己就能接上父子关系。
 *
 * <p>线程边界要显式搬运：{@link #call(Frame, Supplier)} 把快照带进线程池任务；
 * 任务结束自动还原，避免线程复用把 A 请求的上下文漏给 B 请求。
 */
public final class TraceContext {

    public record Frame(String traceId, String spanId, String owner, String patientId,
                        String businessType, String businessId, java.time.OffsetDateTime acceptedAt) {

        /** 只换 span，其余字段沿用。 */
        public Frame withSpan(String nextSpanId) {
            return new Frame(traceId, nextSpanId, owner, patientId, businessType, businessId, acceptedAt);
        }
    }

    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();

    private TraceContext() {
    }

    public static Frame current() {
        return CURRENT.get();
    }

    public static String currentTraceId() {
        Frame frame = CURRENT.get();
        return frame == null ? null : frame.traceId();
    }

    public static void set(Frame frame) {
        if (frame == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(frame);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 带着快照执行，结束后恢复到进入前的状态（可能是无上下文）。 */
    public static <T> T call(Frame frame, Supplier<T> body) {
        Frame previous = CURRENT.get();
        set(frame);
        try {
            return body.get();
        } finally {
            set(previous);
        }
    }

    public static void run(Frame frame, Runnable body) {
        call(frame, () -> {
            body.run();
            return null;
        });
    }
}
