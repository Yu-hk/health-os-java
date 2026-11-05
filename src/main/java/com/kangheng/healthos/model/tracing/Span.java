package com.kangheng.healthos.model.tracing;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.model.tracing.TraceSpanEntity;

/**
 * 一个进行中的操作。用法与 {@code try-with-resources} 对齐：
 *
 * <pre>{@code
 * try (Span span = tracing.start("llm.extract", "model", "llm")) {
 *     span.attribute("chars", segment.length());
 *     return provider.extract(title, text);
 * }
 * }</pre>
 *
 * <p>关闭时落库；异常由调用方（{@link TracingService#span}）标记失败后照常抛出，
 * 观测永远不改变业务结果。
 */
public final class Span implements AutoCloseable {

    /** 属性值统一截断，避免把整段正文写进轨迹。 */
    private static final int MAX_ATTRIBUTE_LENGTH = 200;

    private final TraceSpanEntity entity;
    private final Consumer<TraceSpanEntity> sink;
    private final Runnable afterClose;
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private final OffsetDateTime startedAt;
    private boolean closed;

    public Span(TraceSpanEntity entity, Consumer<TraceSpanEntity> sink, Runnable afterClose) {
        this.entity = entity;
        this.sink = sink;
        this.afterClose = afterClose;
        this.startedAt = entity.getStartedAt();
    }

    /**
     * 不落库的占位 span：未开启观测、无上下文、或超过单轨迹上限时返回它。
     * 调用方照常 {@code try (Span s = ...)}，不必到处判空。
     */
    public static Span discarded() {
        TraceSpanEntity entity = new TraceSpanEntity();
        entity.setTraceId("");
        entity.setSpanId("");
        entity.setName("discarded");
        entity.setKind("discarded");
        entity.setService("discarded");
        entity.setStartedAt(OffsetDateTime.now());
        return new Span(entity, row -> {
        }, null);
    }

    public String spanId() {
        return entity.getSpanId();
    }

    public String traceId() {
        return entity.getTraceId();
    }

    public Span attribute(String key, Object value) {
        if (key == null || value == null) {
            return this;
        }
        if (value instanceof String text) {
            attributes.put(key, text.length() > MAX_ATTRIBUTE_LENGTH
                    ? text.substring(0, MAX_ATTRIBUTE_LENGTH) : text);
        } else if (value instanceof Number || value instanceof Boolean) {
            attributes.put(key, value);
        } else {
            String text = String.valueOf(value);
            attributes.put(key, text.length() > MAX_ATTRIBUTE_LENGTH ? text.substring(0, MAX_ATTRIBUTE_LENGTH) : text);
        }
        return this;
    }

    public Span attributes(Map<String, Object> values) {
        if (values != null) {
            values.forEach(this::attribute);
        }
        return this;
    }

    /** 标记失败。错误码沿用业务错误码，便于和日志对齐。 */
    public Span failed(String errorCode, String errorMessage) {
        entity.setStatus("FAILED");
        entity.setErrorCode(errorCode);
        if (errorMessage != null) {
            entity.setErrorMessage(errorMessage.length() > 1000
                    ? errorMessage.substring(0, 1000) : errorMessage);
        }
        return this;
    }

    public boolean failed() {
        return "FAILED".equals(entity.getStatus());
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        OffsetDateTime now = OffsetDateTime.now();
        entity.setCompletedAt(now);
        entity.setDurationMs(Math.max(0, Duration.between(startedAt, now).toMillis()));
        if (entity.getStatus() == null) {
            entity.setStatus("OK");
        }
        if (!attributes.isEmpty()) {
            entity.setAttributes(Json.write(attributes));
        }
        try {
            sink.accept(entity);
        } catch (RuntimeException ignored) {
            // 观测写失败不能反向影响业务：这里只吞掉，由 writer 的丢弃计数体现。
        }
        if (afterClose != null) {
            try {
                afterClose.run();
            } catch (RuntimeException ignored) {
                // 同上：上下文还原失败不应影响调用方。
            }
        }
    }
}
