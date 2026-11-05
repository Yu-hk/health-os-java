package com.kangheng.healthos.service.tracing;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import com.kangheng.healthos.common.AccessControl;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.common.PageResult;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.contract.tracing.TracingContract;
import com.kangheng.healthos.mapper.tracing.TraceEventMapper;
import com.kangheng.healthos.mapper.tracing.TraceRunMapper;
import com.kangheng.healthos.mapper.tracing.TraceSpanMapper;
import com.kangheng.healthos.model.tracing.TraceEventEntity;
import com.kangheng.healthos.model.tracing.TraceRunEntity;
import com.kangheng.healthos.model.tracing.TraceSpanEntity;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 轨迹查询。
 *
 * <p>每个入口先 {@code flush()}：写入是异步的，不刷一下就可能查不到刚发生的
 * 那条轨迹——"刚跑完点进去什么都没有"是观测系统最劝退的体验。
 */
@Service
public class TraceQueryService {

    private static final int MAX_TREE_DEPTH = 20;

    private final TraceRunMapper runMapper;
    private final TraceSpanMapper spanMapper;
    private final TraceEventMapper eventMapper;
    private final TracingService tracing;
    private final HealthOsProperties properties;
    private final AccessControl accessControl;

    public TraceQueryService(TraceRunMapper runMapper,
                             TraceSpanMapper spanMapper,
                             TraceEventMapper eventMapper,
                             TracingService tracing,
                             HealthOsProperties properties,
                             AccessControl accessControl) {
        this.runMapper = runMapper;
        this.spanMapper = spanMapper;
        this.eventMapper = eventMapper;
        this.tracing = tracing;
        this.properties = properties;
        this.accessControl = accessControl;
    }

    /**
     * 轨迹列表。
     *
     * <p>默认只看得到自己发起的轨迹。{@code owner} 参数只是"在我的轨迹里筛人"，
     * 不是"看别人的轨迹"的通行证——真要看别人的，得在全局读白名单里。
     */
    public PageResult<TracingContract.TraceSummary> list(String businessType, String businessId,
                                                         String owner, String status,
                                                         OffsetDateTime from, OffsetDateTime to,
                                                         int page, int size, String caller) {
        tracing.flush();
        boolean global = accessControl.isTraceGlobalReader(caller);
        if (!global && StringUtils.hasText(owner) && !owner.equals(caller)) {
            throw new BizException("TRACE_SCOPE_FORBIDDEN", "无权查看他人轨迹", HttpStatus.FORBIDDEN);
        }
        // 非全局读者：owner 条件强制收敛到本人，哪怕没传 owner
        String effectiveOwner = global ? owner : caller;
        LambdaQueryWrapper<TraceRunEntity> wrapper = new LambdaQueryWrapper<TraceRunEntity>()
                .eq(StringUtils.hasText(businessType), TraceRunEntity::getBusinessType, businessType)
                .eq(StringUtils.hasText(businessId), TraceRunEntity::getBusinessId, businessId)
                .eq(StringUtils.hasText(effectiveOwner), TraceRunEntity::getOwner, effectiveOwner)
                .eq(StringUtils.hasText(status), TraceRunEntity::getStatus, status)
                .ge(from != null, TraceRunEntity::getAcceptedAt, from)
                .le(to != null, TraceRunEntity::getAcceptedAt, to)
                .orderByDesc(TraceRunEntity::getAcceptedAt);
        Page<TraceRunEntity> result = runMapper.selectPage(
                new Page<>(PageResult.clampPage(page), PageResult.clampSize(size)), wrapper);
        Map<String, List<TraceSpanEntity>> spans = spansByTrace(
                result.getRecords().stream().map(TraceRunEntity::getTraceId).toList());
        return PageResult.of(result,
                run -> summary(run, spans.getOrDefault(run.getTraceId(), List.of())));
    }

    /**
     * 轨迹详情。
     *
     * <p>越权返回 404：轨迹里有问答原文与患者 ID，403 等于承认"这条轨迹存在"。
     * 原项目 {@code tracing_access.visible()} 用的是同一个判断。
     */
    public TracingContract.TraceDetail detail(String traceId, String caller) {
        tracing.flush();
        TraceRunEntity run = runMapper.selectById(traceId);
        if (run == null || !canRead(run, caller)) {
            throw BizException.notFound("轨迹");
        }
        List<TraceSpanEntity> spans = spanMapper.selectList(new LambdaQueryWrapper<TraceSpanEntity>()
                .eq(TraceSpanEntity::getTraceId, traceId)
                .orderByAsc(TraceSpanEntity::getStartedAt));
        List<TraceEventEntity> events = eventMapper.selectList(new LambdaQueryWrapper<TraceEventEntity>()
                .eq(TraceEventEntity::getTraceId, traceId)
                .orderByAsc(TraceEventEntity::getCreatedAt));

        List<String> warnings = new ArrayList<>();
        if (run.getCompletedAt() == null && "RUNNING".equals(run.getStatus())) {
            warnings.add("轨迹尚未收尾：进程可能在执行中退出，耗时为不完整值");
        }
        List<TracingContract.SpanView> tree = buildTree(spans, warnings);

        List<TracingContract.EventView> eventViews = events.stream()
                .map(event -> new TracingContract.EventView(event.getId(), event.getSpanId(),
                        event.getName(), event.getDetail() == null ? null : Json.readMap(event.getDetail()),
                        event.getCreatedAt()))
                .toList();
        return new TracingContract.TraceDetail(summary(run, spans), tree, eventViews, warnings);
    }

    public TracingContract.Overview overview(OffsetDateTime from, OffsetDateTime to, String caller) {
        tracing.flush();
        OffsetDateTime start = from == null ? OffsetDateTime.now().minusDays(1) : from;
        OffsetDateTime end = to == null ? OffsetDateTime.now() : to;
        int cap = Math.max(100, properties.getTracing().getOverviewMaxRows());
        boolean global = accessControl.isTraceGlobalReader(caller);

        List<TraceRunEntity> runs = runMapper.selectList(new LambdaQueryWrapper<TraceRunEntity>()
                .eq(!global, TraceRunEntity::getOwner, caller)
                .ge(TraceRunEntity::getAcceptedAt, start)
                .le(TraceRunEntity::getAcceptedAt, end)
                .orderByDesc(TraceRunEntity::getAcceptedAt)
                .last("limit " + cap));
        // span 按轨迹取，而不是全表扫：否则总览里会混进别人的 span
        Map<String, List<TraceSpanEntity>> spansByTraceId = spansByTrace(
                runs.stream().map(TraceRunEntity::getTraceId).toList());
        List<TraceSpanEntity> capped = spansByTraceId.values().stream()
                .flatMap(List::stream)
                .limit(cap)
                .toList();

        Map<String, Long> byStatus = new LinkedHashMap<>();
        Map<String, Long> byBusinessType = new LinkedHashMap<>();
        long failed = 0;
        long running = 0;
        for (TraceRunEntity run : runs) {
            byStatus.merge(nullToUnknown(run.getStatus()), 1L, Long::sum);
            byBusinessType.merge(nullToUnknown(run.getBusinessType()), 1L, Long::sum);
            if ("FAILED".equals(run.getStatus())) {
                failed++;
            }
            if ("RUNNING".equals(run.getStatus())) {
                running++;
            }
        }

        Map<String, List<Long>> durations = new LinkedHashMap<>();
        Map<String, Long> failures = new HashMap<>();
        for (TraceSpanEntity span : capped) {
            durations.computeIfAbsent(nullToUnknown(span.getService()), key -> new ArrayList<>())
                    .add(span.getDurationMs() == null ? 0L : span.getDurationMs());
            if ("FAILED".equals(span.getStatus())) {
                failures.merge(span.getService(), 1L, Long::sum);
            }
        }
        List<TracingContract.ServiceStat> stats = new ArrayList<>();
        for (Map.Entry<String, List<Long>> entry : durations.entrySet()) {
            List<Long> values = entry.getValue();
            values.sort(Comparator.naturalOrder());
            stats.add(new TracingContract.ServiceStat(entry.getKey(), values.size(),
                    failures.getOrDefault(entry.getKey(), 0L),
                    (long) values.stream().mapToLong(Long::longValue).average().orElse(0),
                    percentile(values, 0.50), percentile(values, 0.95),
                    values.get(values.size() - 1)));
        }
        stats.sort(Comparator.comparingLong(TracingContract.ServiceStat::p95Ms).reversed());

        return new TracingContract.Overview(runs.size(), failed, running, capped.size(),
                byStatus, byBusinessType, stats, start, end);
    }

    // ------------------------------------------------------------ 内部

    /** 能不能读这条轨迹：本人，或在全局读白名单里。 */
    private boolean canRead(TraceRunEntity run, String caller) {
        if (run == null) {
            return false;
        }
        if (AccessControl.sameUser(caller, run.getOwner())) {
            return true;
        }
        return accessControl.isTraceGlobalReader(caller);
    }

    private Map<String, List<TraceSpanEntity>> spansByTrace(List<String> traceIds) {
        if (traceIds.isEmpty()) {
            return Map.of();
        }
        List<TraceSpanEntity> spans = spanMapper.selectList(new LambdaQueryWrapper<TraceSpanEntity>()
                .in(TraceSpanEntity::getTraceId, traceIds));
        Map<String, List<TraceSpanEntity>> grouped = new LinkedHashMap<>();
        for (TraceSpanEntity span : spans) {
            grouped.computeIfAbsent(span.getTraceId(), key -> new ArrayList<>()).add(span);
        }
        return grouped;
    }

    private TracingContract.TraceSummary summary(TraceRunEntity run, List<TraceSpanEntity> spans) {
        int failedSpans = (int) spans.stream().filter(s -> "FAILED".equals(s.getStatus())).count();
        OffsetDateTime completed = run.getCompletedAt();
        Long duration = null;
        if (run.getAcceptedAt() != null) {
            OffsetDateTime end = completed == null ? OffsetDateTime.now() : completed;
            duration = Math.max(0, java.time.Duration.between(run.getAcceptedAt(), end).toMillis());
        }
        return new TracingContract.TraceSummary(run.getTraceId(), run.getBusinessType(), run.getBusinessId(),
                run.getExecutionNo(), run.getOwner(), run.getPatientId(), run.getStatus(),
                run.getOutcome(), run.getErrorCode(), run.getErrorMessage(),
                run.getAcceptedAt(), completed, duration,
                spans.size(), failedSpans);
    }

    private List<TracingContract.SpanView> buildTree(List<TraceSpanEntity> spans, List<String> warnings) {
        Set<String> known = new LinkedHashSet<>();
        for (TraceSpanEntity span : spans) {
            known.add(span.getSpanId());
        }
        Map<String, List<TraceSpanEntity>> byParent = new LinkedHashMap<>();
        int orphaned = 0;
        for (TraceSpanEntity span : spans) {
            String parent = span.getParentSpanId();
            if (parent != null && !known.contains(parent)) {
                orphaned++;
                parent = null;
            }
            byParent.computeIfAbsent(parent, key -> new ArrayList<>()).add(span);
        }
        if (orphaned > 0) {
            warnings.add(orphaned + " 个 span 的父节点已不在本轨迹内，已按顶层节点展示");
        }
        List<TracingContract.SpanView> roots = new ArrayList<>();
        for (TraceSpanEntity root : byParent.getOrDefault(null, List.of())) {
            roots.add(toView(root, byParent, 0, warnings));
        }
        return roots;
    }

    private TracingContract.SpanView toView(TraceSpanEntity span,
                                            Map<String, List<TraceSpanEntity>> byParent,
                                            int depth,
                                            List<String> warnings) {
        List<TracingContract.SpanView> children = new ArrayList<>();
        if (depth < MAX_TREE_DEPTH) {
            for (TraceSpanEntity child : byParent.getOrDefault(span.getSpanId(), List.of())) {
                children.add(toView(child, byParent, depth + 1, warnings));
            }
        } else if (byParent.containsKey(span.getSpanId())) {
            warnings.add("span 树深度超过 " + MAX_TREE_DEPTH + "，更深的层级未展开");
        }
        return new TracingContract.SpanView(span.getSpanId(), span.getParentSpanId(), span.getName(),
                span.getKind(), span.getService(), span.getStatus(), span.getErrorCode(),
                span.getErrorMessage(),
                span.getAttributes() == null ? Map.of() : Json.readMap(span.getAttributes()),
                span.getStartedAt(), span.getCompletedAt(), span.getDurationMs(), children);
    }

    private static String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    private static long percentile(List<Long> sorted, double ratio) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(ratio * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }
}
