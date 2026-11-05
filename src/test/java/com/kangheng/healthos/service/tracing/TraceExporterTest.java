package com.kangheng.healthos.service.tracing;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.kangheng.healthos.contract.tracing.TracingContract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceExporterTest {

    private final TraceExporter exporter = new TraceExporter();

    private static TracingContract.TraceSummary summary() {
        OffsetDateTime start = OffsetDateTime.parse("2026-11-03T10:00:00+08:00");
        return new TracingContract.TraceSummary("abc123", "classic_document", "doc_1", 1, "admin", null,
                "SUCCEEDED", "SUCCEEDED", null, null, start, start.plusSeconds(3), 3000L, 2, 1);
    }

    private static TracingContract.SpanView span(String id, String parent, String name, String kind,
                                                 String service, String status) {
        OffsetDateTime start = OffsetDateTime.parse("2026-11-03T10:00:01+08:00");
        return new TracingContract.SpanView(id, parent, name, kind, service, status,
                "FAILED".equals(status) ? "LLM_FAILED" : null,
                "FAILED".equals(status) ? "模型超时" : null,
                Map.of("provider", "template", "chars", 120),
                start, start.plusNanos(500_000_000L), 500L, List.of());
    }

    @Test
    void producesOtlpShapeWithStringNanos() {
        TracingContract.SpanView root = new TracingContract.SpanView("s1", null, "root", "business",
                "classic_document", "OK", null, null, Map.of(),
                OffsetDateTime.parse("2026-11-03T10:00:00+08:00"),
                OffsetDateTime.parse("2026-11-03T10:00:02+08:00"), 2000L,
                List.of(span("s2", "s1", "tcm_extract", "model", "llm", "OK")));

        Map<String, Object> otlp = exporter.toOtlp(
                new TracingContract.TraceDetail(summary(), List.of(root), List.of(), List.of()));

        List<?> resourceSpans = (List<?>) otlp.get("resourceSpans");
        assertEquals(1, resourceSpans.size());
        Map<?, ?> first = (Map<?, ?>) resourceSpans.get(0);
        Map<?, ?> scopeSpans = (Map<?, ?>) ((List<?>) first.get("scopeSpans")).get(0);
        List<?> spans = (List<?>) scopeSpans.get("spans");
        assertEquals(2, spans.size(), "树要拍平成 span 列表");

        Map<?, ?> child = (Map<?, ?>) spans.get(1);
        assertEquals("abc123", child.get("traceId"), "所有 span 共用同一个 traceId");
        assertEquals("s2", child.get("spanId"));
        assertEquals("s1", child.get("parentSpanId"));
        assertEquals(3, child.get("kind"), "model 类操作按 CLIENT 导出");
        assertTrue(child.get("startTimeUnixNano") instanceof String);
        assertTrue(((String) child.get("startTimeUnixNano")).matches("\\d+"),
                "OTLP 要求 64 位纳秒用字符串表示，数字会在大数下丢精度");
    }

    @Test
    void mapsFailedStatusToErrorCode() {
        TracingContract.SpanView failed = span("s9", null, "llm.extract", "model", "llm", "FAILED");
        Map<String, Object> otlp = exporter.toOtlp(
                new TracingContract.TraceDetail(summary(), List.of(failed), List.of(), List.of()));
        Map<?, ?> span = (Map<?, ?>) ((List<?>) ((Map<?, ?>) ((List<?>) ((Map<?, ?>) ((List<?>) otlp
                .get("resourceSpans")).get(0)).get("scopeSpans")).get(0)).get("spans")).get(0);
        Map<?, ?> status = (Map<?, ?>) span.get("status");
        assertEquals(2, status.get("code"));
        assertEquals("模型超时", status.get("message"));
    }

    @Test
    void attributesKeepTypes() {
        TracingContract.SpanView view = span("s3", null, "x", "ocr", "ocr", "OK");
        Map<String, Object> otlp = exporter.toOtlp(
                new TracingContract.TraceDetail(summary(), List.of(view), List.of(), List.of()));
        String json = String.valueOf(otlp);
        assertTrue(json.contains("intValue=120"), "数字属性导出为 intValue，不要一律字符串化");
        assertTrue(json.contains("stringValue=template"));
        assertFalse(json.contains("nullValue"));
    }
}
