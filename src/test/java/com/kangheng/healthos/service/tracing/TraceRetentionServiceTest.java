package com.kangheng.healthos.service.tracing;

import java.time.OffsetDateTime;
import java.util.List;

import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.contract.tracing.TracingContract;
import com.kangheng.healthos.mapper.tracing.TraceEventMapper;
import com.kangheng.healthos.mapper.tracing.TraceMaintenanceStateMapper;
import com.kangheng.healthos.mapper.tracing.TraceRunMapper;
import com.kangheng.healthos.mapper.tracing.TraceSpanMapper;
import com.kangheng.healthos.model.tracing.TraceRunEntity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.kangheng.healthos.model.tracing.TraceMaintenanceStateEntity;
import com.kangheng.healthos.spi.tracing.TraceWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceRetentionServiceTest {

    private TraceRunMapper runMapper;
    private TraceSpanMapper spanMapper;
    private TraceEventMapper eventMapper;
    private TraceMaintenanceStateMapper stateMapper;
    private TraceRetentionService service;

    private final OffsetDateTime now = OffsetDateTime.parse("2026-11-03T03:30:00+08:00");

    @BeforeEach
    void setUp() {
        runMapper = mock(TraceRunMapper.class);
        spanMapper = mock(TraceSpanMapper.class);
        eventMapper = mock(TraceEventMapper.class);
        stateMapper = mock(TraceMaintenanceStateMapper.class);
        TracingService tracing = mock(TracingService.class);
        when(tracing.enabled()).thenReturn(true);
        when(tracing.stats()).thenReturn(new TraceWriter.Stats("async", 0, 0, 0, 0));
        service = new TraceRetentionService(runMapper, spanMapper, eventMapper, stateMapper,
                tracing, new HealthOsProperties());
    }

    private static TraceRunEntity run(String id) {
        TraceRunEntity run = new TraceRunEntity();
        run.setTraceId(id);
        run.setExpiresAt(OffsetDateTime.parse("2026-10-01T00:00:00+08:00"));
        return run;
    }

    @Test
    void dryRunCountsButNeverDeletes() {
        when(runMapper.selectList(any())).thenReturn(List.of(run("a"), run("b")));
        when(spanMapper.selectCount(any())).thenReturn(5L);
        when(eventMapper.selectCount(any())).thenReturn(3L);

        TracingContract.SweepResult result = service.sweep(true, now);

        assertTrue(result.dryRun());
        assertEquals(2, result.traces());
        assertTrue(result.spans() >= 5);
        assertTrue(result.events() >= 3);
        verify(runMapper, never()).delete(any());
        verify(spanMapper, never()).delete(any());
        verify(eventMapper, never()).delete(any());
        verify(stateMapper, never()).insert(any(com.kangheng.healthos.model.tracing.TraceMaintenanceStateEntity.class));
    }

    @Test
    void realSweepDeletesInBatchesAndRemembersState() {
        when(runMapper.selectList(any())).thenReturn(List.of(run("a")), List.of());
        when(spanMapper.delete(any())).thenReturn(4);
        when(eventMapper.delete(any())).thenReturn(2);
        when(runMapper.delete(any())).thenReturn(1);
        when(stateMapper.selectById(any())).thenReturn(null);

        TracingContract.SweepResult result = service.sweep(false, now);

        assertFalse(result.dryRun());
        assertEquals(1, result.traces());
        assertEquals(8, result.spans(), "批次内 4 条 + 兜底清理 4 条");
        assertEquals(4, result.events());
        assertFalse(result.truncated());

        ArgumentCaptor<com.kangheng.healthos.model.tracing.TraceMaintenanceStateEntity> captor =
                ArgumentCaptor.forClass(
                        com.kangheng.healthos.model.tracing.TraceMaintenanceStateEntity.class);
        verify(stateMapper).insert(captor.capture());
        assertEquals("daily", captor.getValue().getId());
        assertEquals(now.plusDays(1), captor.getValue().getNextRunAt(),
                "下次执行时间要落库，重启后不能立刻重跑");
        assertTrue(captor.getValue().getLastResult().contains("\"traces\":1"));
    }

    @Test
    void cutoffFollowsConfiguredRetentionDays() {
        HealthOsProperties properties = new HealthOsProperties();
        properties.getTracing().setRetentionDays(7);
        TracingService tracing = mock(TracingService.class);
        when(tracing.enabled()).thenReturn(true);
        when(tracing.stats()).thenReturn(new TraceWriter.Stats("async", 0, 0, 0, 0));
        TraceRetentionService custom = new TraceRetentionService(runMapper, spanMapper, eventMapper,
                stateMapper, tracing, properties);
        when(runMapper.selectList(any())).thenReturn(List.of());
        when(spanMapper.selectCount(any())).thenReturn(0L);
        when(eventMapper.selectCount(any())).thenReturn(0L);

        TracingContract.SweepResult result = custom.sweep(true, now);

        assertEquals(now.minusDays(7), result.cutoff());
    }
}
