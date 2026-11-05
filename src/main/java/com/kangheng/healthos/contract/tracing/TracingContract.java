package com.kangheng.healthos.contract.tracing;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 观测接口的返回体。刻意不返回原始 attributes 之外的任何内部字段。 */
public final class TracingContract {

    private TracingContract() {
    }

    public record TraceSummary(String traceId, String businessType, String businessId, Integer executionNo,
                               String owner, String patientId, String status, String outcome, String errorCode,
                               String errorMessage,
                               OffsetDateTime acceptedAt, OffsetDateTime completedAt, Long durationMs,
                               int spanCount, int failedSpanCount) {
    }

    public record SpanView(String spanId, String parentSpanId, String name, String kind, String service,
                           String status, String errorCode, String errorMessage,
                           Map<String, Object> attributes, OffsetDateTime startedAt,
                           OffsetDateTime completedAt, Long durationMs, List<SpanView> children) {
    }

    public record EventView(Long id, String spanId, String name, Object detail, OffsetDateTime createdAt) {
    }

    /**
     * @param warnings 诊断提示，例如"轨迹没有收尾"、"存在找不到父节点的 span"。
     *                 直接说出来比让使用者对着一张不完整的树猜要诚实。
     */
    public record TraceDetail(TraceSummary trace, List<SpanView> spans, List<EventView> events,
                              List<String> warnings) {
    }

    public record ServiceStat(String service, long count, long failed, long avgMs, long p50Ms,
                              long p95Ms, long maxMs) {
    }

    public record Overview(long traces, long failedTraces, long runningTraces, long spans,
                           Map<String, Long> byStatus, Map<String, Long> byBusinessType,
                           List<ServiceStat> byService, OffsetDateTime from, OffsetDateTime to) {
    }

    public record SweepResult(boolean dryRun, int traces, int spans, int events, boolean truncated,
                              OffsetDateTime cutoff, long elapsedMs) {
    }

    public record MaintenanceView(boolean enabled, String writer, long submitted, long written,
                                  long dropped, int queueDepth, int retentionDays, String cron,
                                  OffsetDateTime lastRunAt, OffsetDateTime nextRunAt, String lastResult) {
    }
}
