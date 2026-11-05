package com.kangheng.healthos.service.tracing;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.contract.tracing.TracingContract;
import com.kangheng.healthos.mapper.tracing.TraceEventMapper;
import com.kangheng.healthos.mapper.tracing.TraceMaintenanceStateMapper;
import com.kangheng.healthos.mapper.tracing.TraceRunMapper;
import com.kangheng.healthos.mapper.tracing.TraceSpanMapper;
import com.kangheng.healthos.model.tracing.TraceEventEntity;
import com.kangheng.healthos.model.tracing.TraceMaintenanceStateEntity;
import com.kangheng.healthos.model.tracing.TraceRunEntity;
import com.kangheng.healthos.model.tracing.TraceSpanEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.kangheng.healthos.spi.tracing.TraceWriter;

/**
 * 轨迹保留策略。
 *
 * <p>三条边界，缺一条都会出事：
 * <ol>
 *   <li><b>只删观测数据</b>：SQL 里只有 trace_* 四张表，业务表与业务对象前缀从不出现；</li>
 *   <li><b>有界</b>：每次最多 {@code sweepMaxBatches × sweepBatchSize} 条，避免一次清理
 *       把数据库锁住；没清完就下次继续，不追求"一次扫表"；</li>
 *   <li><b>可预演</b>：{@code dryRun=true} 只统计不删除，先用它确认"要删多少"再动手。</li>
 * </ol>
 *
 * <p>过期判定用 {@code trace_run.expires_at}（写入时按当时的保留天数算好），
 * 而不是"当前时间 - 保留天数"：改小保留天数后，新老数据才会按各自承诺的期限走。
 */
@Service
public class TraceRetentionService {

    private static final Logger log = LoggerFactory.getLogger(TraceRetentionService.class);

    private static final String STATE_ID = "daily";

    private final TraceRunMapper runMapper;
    private final TraceSpanMapper spanMapper;
    private final TraceEventMapper eventMapper;
    private final TraceMaintenanceStateMapper stateMapper;
    private final TracingService tracing;
    private final HealthOsProperties properties;

    public TraceRetentionService(TraceRunMapper runMapper,
                                 TraceSpanMapper spanMapper,
                                 TraceEventMapper eventMapper,
                                 TraceMaintenanceStateMapper stateMapper,
                                 TracingService tracing,
                                 HealthOsProperties properties) {
        this.runMapper = runMapper;
        this.spanMapper = spanMapper;
        this.eventMapper = eventMapper;
        this.stateMapper = stateMapper;
        this.tracing = tracing;
        this.properties = properties;
    }

    @Scheduled(cron = "${healthos.tracing.retention-cron:0 30 3 * * *}")
    public void scheduled() {
        if (!tracing.enabled()) {
            return;
        }
        try {
            OffsetDateTime now = OffsetDateTime.now();
            TraceMaintenanceStateEntity state = stateMapper.selectById(STATE_ID);
            if (state != null && state.getNextRunAt() != null && state.getNextRunAt().isAfter(now)) {
                return;
            }
            TracingContract.SweepResult result = sweep(false, now);
            log.info("轨迹保留清理完成 traces={} spans={} events={} truncated={} elapsed={}ms",
                    result.traces(), result.spans(), result.events(), result.truncated(),
                    result.elapsedMs());
        } catch (RuntimeException ex) {
            log.warn("轨迹保留清理失败，下次继续", ex);
        }
    }

    public TracingContract.SweepResult sweep(boolean dryRun, OffsetDateTime now) {
        long begun = System.currentTimeMillis();
        OffsetDateTime cutoff = now.minusDays(Math.max(1, properties.getTracing().getRetentionDays()));
        int batch = Math.max(1, properties.getTracing().getSweepBatchSize());
        int maxBatches = Math.max(1, properties.getTracing().getSweepMaxBatches());

        int traces = 0;
        int spans = 0;
        int events = 0;
        boolean truncated = false;

        for (int round = 0; round < maxBatches; round++) {
            List<TraceRunEntity> expired = runMapper.selectList(new LambdaQueryWrapper<TraceRunEntity>()
                    .le(TraceRunEntity::getExpiresAt, now)
                    .orderByAsc(TraceRunEntity::getExpiresAt)
                    .last("limit " + batch));
            if (expired.isEmpty()) {
                break;
            }
            List<String> ids = expired.stream().map(TraceRunEntity::getTraceId).toList();
            if (dryRun) {
                spans += Math.toIntExact(spanMapper.selectCount(new LambdaQueryWrapper<TraceSpanEntity>()
                        .in(TraceSpanEntity::getTraceId, ids)));
                events += Math.toIntExact(eventMapper.selectCount(new LambdaQueryWrapper<TraceEventEntity>()
                        .in(TraceEventEntity::getTraceId, ids)));
                traces += ids.size();
                // 预演只看第一批，给出"量级"就够；继续下去对线上没有额外价值。
                truncated = expired.size() >= batch;
                break;
            }
            spans += spanMapper.delete(new LambdaQueryWrapper<TraceSpanEntity>()
                    .in(TraceSpanEntity::getTraceId, ids));
            events += eventMapper.delete(new LambdaQueryWrapper<TraceEventEntity>()
                    .in(TraceEventEntity::getTraceId, ids));
            traces += runMapper.delete(new LambdaQueryWrapper<TraceRunEntity>()
                    .in(TraceRunEntity::getTraceId, ids));
            if (expired.size() < batch) {
                break;
            }
            if (round == maxBatches - 1) {
                truncated = true;
            }
        }

        // 兜底：run 已被删掉但 span/event 残留（历史遗留或异常中断）
        LambdaQueryWrapper<TraceSpanEntity> orphanSpans = new LambdaQueryWrapper<TraceSpanEntity>()
                .lt(TraceSpanEntity::getStartedAt, cutoff);
        LambdaQueryWrapper<TraceEventEntity> orphanEvents = new LambdaQueryWrapper<TraceEventEntity>()
                .lt(TraceEventEntity::getCreatedAt, cutoff);
        if (dryRun) {
            spans += Math.toIntExact(spanMapper.selectCount(orphanSpans));
            events += Math.toIntExact(eventMapper.selectCount(orphanEvents));
        } else {
            spans += spanMapper.delete(orphanSpans);
            events += eventMapper.delete(orphanEvents);
        }

        TracingContract.SweepResult result = new TracingContract.SweepResult(dryRun, traces, spans, events,
                truncated, cutoff, System.currentTimeMillis() - begun);
        if (!dryRun) {
            recordState(now, result);
        }
        return result;
    }

    public TracingContract.MaintenanceView maintenance() {
        TraceWriter.Stats stats = tracing.stats();
        TraceMaintenanceStateEntity state = stateMapper.selectById(STATE_ID);
        return new TracingContract.MaintenanceView(tracing.enabled(), stats.mode(), stats.submitted(),
                stats.written(), stats.dropped(), stats.queueDepth(),
                properties.getTracing().getRetentionDays(), properties.getTracing().getRetentionCron(),
                state == null ? null : state.getLastRunAt(),
                state == null ? null : state.getNextRunAt(),
                state == null ? null : state.getLastResult());
    }

    private void recordState(OffsetDateTime now, TracingContract.SweepResult result) {
        TraceMaintenanceStateEntity state = stateMapper.selectById(STATE_ID);
        boolean insert = state == null;
        if (insert) {
            state = new TraceMaintenanceStateEntity();
            state.setId(STATE_ID);
        }
        state.setLastRunAt(now);
        state.setNextRunAt(now.plusDays(1));
        state.setLastResult(Json.write(result));
        state.setUpdatedAt(now);
        if (insert) {
            stateMapper.insert(state);
        } else {
            stateMapper.updateById(state);
        }
    }
}
