package com.kangheng.healthos.service.tracing;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.mapper.tracing.TraceEventMapper;
import com.kangheng.healthos.mapper.tracing.TraceRunMapper;
import com.kangheng.healthos.mapper.tracing.TraceSpanMapper;
import com.kangheng.healthos.model.tracing.TraceEventEntity;
import com.kangheng.healthos.model.tracing.TraceRunEntity;
import com.kangheng.healthos.model.tracing.TraceSpanEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.kangheng.healthos.model.tracing.Span;
import com.kangheng.healthos.spi.tracing.TraceWriter;

/**
 * 埋点入口。业务代码只调这里，不碰任何 mapper。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li><b>不改业务结果</b>：所有写入失败都被吞掉并计数，异常照原样抛给调用方；</li>
 *   <li><b>不存敏感内容</b>：attributes 只收白名单类型并截断，绝不整包转储请求体；</li>
 *   <li><b>能关</b>：{@code healthos.tracing.enabled=false} 时所有方法立即返回，
 *       连 ThreadLocal 都不碰，线上的排查开关可以放心随时关闭。</li>
 * </ol>
 */
@Service
public class TracingService {

    private static final Logger log = LoggerFactory.getLogger(TracingService.class);

    /** 超过该长度的字符串属性一律截断，防止正文/提示词混进轨迹。 */
    private static final int MAX_ERROR_LENGTH = 1000;

    private final TraceRunMapper runMapper;
    private final TraceSpanMapper spanMapper;
    private final TraceEventMapper eventMapper;
    private final TraceWriter writer;
    private final HealthOsProperties properties;

    /** 每条轨迹的 span 计数，LRU 上限 256 条，避免未收尾的轨迹长期占内存。 */
    private final Map<String, AtomicInteger> spanCounters = Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AtomicInteger> eldest) {
                    return size() > 256;
                }
            });

    private final Set<String> truncated = ConcurrentHashMap.newKeySet();

    public TracingService(TraceRunMapper runMapper,
                          TraceSpanMapper spanMapper,
                          TraceEventMapper eventMapper,
                          TraceWriter writer,
                          HealthOsProperties properties) {
        this.runMapper = runMapper;
        this.spanMapper = spanMapper;
        this.eventMapper = eventMapper;
        this.writer = writer;
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.getTracing().isEnabled();
    }

    public TraceWriter.Stats stats() {
        return writer.stats();
    }

    public void flush() {
        writer.flush();
    }

    // ------------------------------------------------------------ 轨迹生命周期

    public String beginTrace(String businessType, String businessId, String owner, String patientId) {
        if (!enabled()) {
            return null;
        }
        OffsetDateTime now = OffsetDateTime.now();
        TraceRunEntity previous = latestRun(businessType, businessId);
        String traceId = TraceIds.newTraceId();
        String rootSpanId = TraceIds.newSpanId();

        TraceRunEntity run = new TraceRunEntity();
        run.setTraceId(traceId);
        run.setRootSpanId(rootSpanId);
        run.setBusinessType(truncate(businessType, 40));
        run.setBusinessId(truncate(businessId, 64));
        run.setExecutionNo(previous == null || previous.getExecutionNo() == null
                ? 1 : previous.getExecutionNo() + 1);
        run.setOwner(owner);
        run.setPatientId(patientId);
        run.setPreviousTraceId(previous == null ? null : previous.getTraceId());
        run.setStatus("RUNNING");
        run.setAcceptedAt(now);
        run.setExpiresAt(now.plusDays(Math.max(1, properties.getTracing().getRetentionDays())));
        writer.submit("trace_run", () -> runMapper.insert(run));

        TraceContext.set(new TraceContext.Frame(traceId, rootSpanId, owner, patientId,
                businessType, businessId, now));
        return traceId;
    }

    public void finishTrace(String status) {
        finishTrace(status, null, null);
    }

    /**
     * 收尾。{@code status} 传业务侧原生词（如 {@code OK} / {@code SUCCEEDED} /
     * {@code INSUFFICIENT_EVIDENCE}），这里统一折算成健康度词表再落库，
     * 原生词另存 {@code outcome}。
     *
     * <p>不在调用方各自归一：调用方按业务说话最自然，一旦允许它们各写各的状态，
     * {@code trace_run.status} 就成了自由文本，按状态聚合、算失败率全都失准。
     */
    public void finishTrace(String status, String errorCode, String errorMessage) {
        if (!enabled()) {
            return;
        }
        TraceContext.Frame frame = TraceContext.current();
        if (frame == null) {
            return;
        }
        String health = normalizeStatus(status);
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime startedAt = frame.acceptedAt() == null ? now : frame.acceptedAt();

        // 根 span 只有收尾时才知道总耗时，所以它的行在结束时才写。
        TraceSpanEntity root = new TraceSpanEntity();
        root.setTraceId(frame.traceId());
        root.setSpanId(frame.spanId());
        root.setName("root");
        root.setKind("business");
        root.setService(truncate(frame.businessType(), 80));
        root.setStatus("FAILED".equals(health) ? "FAILED" : "OK");
        root.setErrorCode(errorCode);
        root.setErrorMessage(truncate(errorMessage, MAX_ERROR_LENGTH));
        root.setStartedAt(startedAt);
        root.setCompletedAt(now);
        root.setDurationMs(Math.max(0, Duration.between(startedAt, now).toMillis()));
        writer.submit("trace_span", () -> spanMapper.insert(root));

        TraceRunEntity update = new TraceRunEntity();
        update.setTraceId(frame.traceId());
        update.setStatus(health);
        update.setOutcome(truncate(status, 24));
        update.setErrorCode(errorCode);
        update.setErrorMessage(truncate(errorMessage, MAX_ERROR_LENGTH));
        update.setCompletedAt(now);
        writer.submit("trace_run", () -> runMapper.updateById(update));

        spanCounters.remove(frame.traceId());
        truncated.remove(frame.traceId());
        TraceContext.clear();
    }

    /**
     * 业务状态 → 健康度。判断口径是"这次执行有没有把活干完"，而不是"业务满不满意"：
     * {@code INSUFFICIENT_EVIDENCE} 说明流程正常跑完、只是证据不足，属 SUCCEEDED；
     * {@code INTERRUPTED} 是被人为打断，单列一档，既不算成功也不该混进失败率。
     */
    static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "SUCCEEDED";
        }
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "FAILED", "FAIL", "ERROR" -> "FAILED";
            case "INTERRUPTED", "CANCELLED", "CANCELED", "ABORTED" -> "INTERRUPTED";
            default -> "SUCCEEDED";
        };
    }

    // ------------------------------------------------------------ span

    public Span start(String name, String kind, String service) {
        if (!enabled()) {
            return Span.discarded();
        }
        TraceContext.Frame frame = TraceContext.current();
        if (frame == null) {
            return Span.discarded();
        }
        int max = Math.max(10, properties.getTracing().getMaxSpansPerTrace());
        int count = spanCounters.computeIfAbsent(frame.traceId(), key -> new AtomicInteger())
                .incrementAndGet();
        if (count > max) {
            if (truncated.add(frame.traceId())) {
                event("spans_truncated", Map.of("maxSpans", max));
            }
            return Span.discarded();
        }

        TraceSpanEntity entity = new TraceSpanEntity();
        entity.setTraceId(frame.traceId());
        entity.setSpanId(TraceIds.newSpanId());
        entity.setParentSpanId(frame.spanId());
        entity.setName(truncate(name, 160));
        entity.setKind(truncate(kind, 40));
        entity.setService(truncate(service, 80));
        entity.setStartedAt(OffsetDateTime.now());

        TraceContext.set(frame.withSpan(entity.getSpanId()));
        Runnable restore = () -> {
            TraceContext.Frame current = TraceContext.current();
            if (current != null && entity.getSpanId().equals(current.spanId())) {
                TraceContext.set(frame);
            }
        };
        return new Span(entity, row -> writer.submit("trace_span", () -> spanMapper.insert(row)), restore);
    }

    public <T> T span(String name, String kind, String service, Supplier<T> body) {
        return span(name, kind, service, null, body);
    }

    public <T> T span(String name, String kind, String service, Map<String, Object> attributes,
                      Supplier<T> body) {
        if (!enabled()) {
            return body.get();
        }
        try (Span span = start(name, kind, service)) {
            span.attributes(attributes);
            try {
                return body.get();
            } catch (RuntimeException | Error ex) {
                span.failed(errorCodeOf(ex), message(ex));
                throw ex;
            }
        }
    }

    public void span(String name, String kind, String service, Runnable body) {
        span(name, kind, service, () -> {
            body.run();
            return null;
        });
    }

    // ------------------------------------------------------------ 事件

    public void event(String name, Map<String, Object> detail) {
        if (!enabled()) {
            return;
        }
        TraceContext.Frame frame = TraceContext.current();
        if (frame == null) {
            return;
        }
        writeEvent(frame.traceId(), frame.spanId(), name, detail);
    }

    /** 轨迹已结束时补写事件（保留清理、人工处置等）。 */
    public void writeEvent(String traceId, String spanId, String name, Map<String, Object> detail) {
        if (!enabled() || traceId == null) {
            return;
        }
        TraceEventEntity entity = new TraceEventEntity();
        entity.setTraceId(traceId);
        entity.setSpanId(spanId);
        entity.setName(truncate(name, 120));
        entity.setDetail(detail == null ? null : Json.write(detail));
        entity.setCreatedAt(OffsetDateTime.now());
        writer.submit("trace_event", () -> eventMapper.insert(entity));
    }

    // ------------------------------------------------------------ 工具

    /** 跨线程搬运上下文：线程池任务里用它把轨迹接上。 */
    public <T> T callIn(TraceContext.Frame frame, Supplier<T> body) {
        if (!enabled() || frame == null) {
            return body.get();
        }
        return TraceContext.call(frame, body);
    }

    /** 取最近一次同业务对象的轨迹，用于串成执行链。 */
    public TraceRunEntity latestRun(String businessType, String businessId) {
        try {
            return runMapper.selectOne(new LambdaQueryWrapper<TraceRunEntity>()
                    .eq(TraceRunEntity::getBusinessType, businessType)
                    .eq(TraceRunEntity::getBusinessId, businessId)
                    .orderByDesc(TraceRunEntity::getExecutionNo)
                    .last("limit 1"));
        } catch (RuntimeException ex) {
            log.debug("查询上一次轨迹失败 businessType={} businessId={}", businessType, businessId, ex);
            return null;
        }
    }

    private static String errorCodeOf(Throwable ex) {
        if (ex instanceof BizException biz) {
            return biz.getCode();
        }
        return ex.getClass().getSimpleName().toUpperCase();
    }

    private static String message(Throwable ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
