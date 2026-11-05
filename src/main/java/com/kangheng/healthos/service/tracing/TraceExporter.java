package com.kangheng.healthos.service.tracing;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kangheng.healthos.contract.tracing.TracingContract;

import org.springframework.stereotype.Component;

/**
 * 把内部轨迹转成 OTLP/JSON。
 *
 * <p>为什么是 OTLP 而不是自定义格式：导出的价值在于"换个工具还能看"。
 * 这里产出的结构可直接投给 OTLP 兼容后端（Jaeger / Tempo / Collector），
 * 不需要用户为本工程写专用适配器。
 *
 * <p>字段严格按 OTLP 规范：{@code startTimeUnixNano} 必须是字符串形式的纳秒，
 * {@code kind} 是整数枚举，{@code status.code} 用 1=OK / 2=ERROR。
 */
@Component
public class TraceExporter {

    private static final String SCOPE_NAME = "healthos-java.tracing";
    private static final String SCOPE_VERSION = "1";

    public Map<String, Object> toOtlp(TracingContract.TraceDetail detail) {
        String traceId = detail.trace() == null ? "" : detail.trace().traceId();
        List<TracingContract.SpanView> flat = new ArrayList<>();
        flatten(detail.spans(), flat);

        List<Map<String, Object>> spans = new ArrayList<>();
        for (TracingContract.SpanView span : flat) {
            spans.add(toSpan(span, traceId));
        }

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("attributes", List.of(
                otlpAttribute("service.name", "health-os-java"),
                otlpAttribute("service.version", "0.1.0")));

        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("name", SCOPE_NAME);
        scope.put("version", SCOPE_VERSION);

        Map<String, Object> scopeSpans = new LinkedHashMap<>();
        scopeSpans.put("scope", scope);
        scopeSpans.put("spans", spans);

        Map<String, Object> resourceSpans = new LinkedHashMap<>();
        resourceSpans.put("resource", resource);
        resourceSpans.put("scopeSpans", List.of(scopeSpans));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("resourceSpans", List.of(resourceSpans));
        return root;
    }

    private Map<String, Object> toSpan(TracingContract.SpanView span, String traceId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("traceId", traceId == null ? "" : traceId);
        out.put("spanId", span.spanId() == null ? "" : span.spanId());
        out.put("name", span.name());
        out.put("kind", kindCode(span.kind()));
        out.put("startTimeUnixNano", nanos(span.startedAt()));
        if (span.completedAt() != null) {
            out.put("endTimeUnixNano", nanos(span.completedAt()));
        }
        List<Map<String, Object>> attributes = new ArrayList<>();
        span.attributes().forEach((key, value) -> attributes.add(otlpAttribute(key, value)));
        attributes.add(otlpAttribute("span.service", span.service()));
        attributes.add(otlpAttribute("span.kind", span.kind()));
        out.put("attributes", attributes);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("code", "FAILED".equals(span.status()) ? 2 : 1);
        if (span.errorMessage() != null) {
            status.put("message", span.errorMessage());
        }
        out.put("status", status);
        out.put("traceState", "");
        if (span.parentSpanId() != null) {
            out.put("parentSpanId", span.parentSpanId());
        }
        return out;
    }

    private static Map<String, Object> otlpAttribute(String key, Object value) {
        Map<String, Object> attribute = new LinkedHashMap<>();
        attribute.put("key", key);
        Map<String, Object> wrapped = new LinkedHashMap<>();
        if (value instanceof Boolean bool) {
            wrapped.put("boolValue", bool);
        } else if (value instanceof Integer || value instanceof Long) {
            wrapped.put("intValue", String.valueOf(value));
        } else if (value instanceof Number number) {
            wrapped.put("doubleValue", number.doubleValue());
        } else {
            wrapped.put("stringValue", String.valueOf(value));
        }
        attribute.put("value", wrapped);
        return attribute;
    }

    /** OTLP SpanKind：0 未指定、1 内部、2 服务端、3 客户端、4 生产者、5 消费者。 */
    private static int kindCode(String kind) {
        if (kind == null) {
            return 1;
        }
        return switch (kind) {
            case "business", "business_stage", "convert" -> 1;
            case "model", "retrieval", "graph", "ocr" -> 3;
            case "queue" -> 4;
            default -> 1;
        };
    }

    private static String nanos(OffsetDateTime time) {
        if (time == null) {
            return "0";
        }
        return String.valueOf(time.toEpochSecond() * 1_000_000_000L + time.getNano());
    }

    private static void flatten(List<TracingContract.SpanView> nodes, List<TracingContract.SpanView> out) {
        for (TracingContract.SpanView node : nodes) {
            out.add(node);
            if (node.children() != null && !node.children().isEmpty()) {
                flatten(node.children(), out);
            }
        }
    }
}
