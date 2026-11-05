package com.kangheng.healthos.service.tracing;

import java.time.OffsetDateTime;
import java.util.Map;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.mapper.tracing.TraceEventMapper;
import com.kangheng.healthos.mapper.tracing.TraceRunMapper;
import com.kangheng.healthos.mapper.tracing.TraceSpanMapper;
import com.kangheng.healthos.model.tracing.TraceEventEntity;
import com.kangheng.healthos.model.tracing.TraceRunEntity;
import com.kangheng.healthos.model.tracing.TraceSpanEntity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.kangheng.healthos.provider.tracing.DirectTraceWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TracingServiceTest {

    private TraceRunMapper runMapper;
    private TraceSpanMapper spanMapper;
    private TraceEventMapper eventMapper;
    private HealthOsProperties properties;
    private TracingService service;

    @BeforeEach
    void setUp() {
        runMapper = mock(TraceRunMapper.class);
        spanMapper = mock(TraceSpanMapper.class);
        eventMapper = mock(TraceEventMapper.class);
        properties = new HealthOsProperties();
        // 同步写入器让断言不依赖线程调度
        service = new TracingService(runMapper, spanMapper, eventMapper,
                new DirectTraceWriter(), properties);
    }

    @AfterEach
    void cleanup() {
        TraceContext.clear();
    }

    @Test
    void beginTraceWritesRunAndPushesContext() {
        when(runMapper.selectOne(any())).thenReturn(null);

        String traceId = service.beginTrace("qa_ask", "doc_1", "admin", "pat_1");

        assertNotNull(traceId);
        assertEquals(32, traceId.length(), "traceId 用 W3C 的 32 位十六进制");
        TraceContext.Frame frame = TraceContext.current();
        assertEquals(traceId, frame.traceId());
        assertEquals("pat_1", frame.patientId());

        ArgumentCaptor<TraceRunEntity> captor = ArgumentCaptor.forClass(TraceRunEntity.class);
        verify(runMapper).insert(captor.capture());
        TraceRunEntity run = captor.getValue();
        assertEquals(1, run.getExecutionNo());
        assertEquals("RUNNING", run.getStatus());
        assertNull(run.getPreviousTraceId());
        assertTrue(run.getExpiresAt().isAfter(OffsetDateTime.now()), "写入时就该定好过期时间");
    }

    @Test
    void reRunChainsToPreviousTrace() {
        TraceRunEntity previous = new TraceRunEntity();
        previous.setTraceId("prev0123456789abcdef0123456789ab");
        previous.setExecutionNo(2);
        when(runMapper.selectOne(any())).thenReturn(previous);

        service.beginTrace("classic_document", "doc_1", "system", null);

        ArgumentCaptor<TraceRunEntity> captor = ArgumentCaptor.forClass(TraceRunEntity.class);
        verify(runMapper).insert(captor.capture());
        assertEquals(3, captor.getValue().getExecutionNo());
        assertEquals("prev0123456789abcdef0123456789ab", captor.getValue().getPreviousTraceId(),
                "重跑不是覆盖，要能顺着 previous_trace_id 串回去");
    }

    @Test
    void spanRecordsDurationAndAttributes() {
        when(runMapper.selectOne(any())).thenReturn(null);
        service.beginTrace("qa_ask", "doc_1", "admin", null);
        String rootSpanId = TraceContext.current().spanId();

        String value = service.span("knowledge.retrieve", "retrieval", "knowledge-base",
                Map.of("topK", 8, "engine", "hybrid"), () -> "hit");

        assertEquals("hit", value);
        ArgumentCaptor<TraceSpanEntity> captor = ArgumentCaptor.forClass(TraceSpanEntity.class);
        verify(spanMapper).insert(captor.capture());
        TraceSpanEntity span = captor.getValue();
        assertEquals("OK", span.getStatus());
        assertEquals("knowledge-base", span.getService());
        assertNotNull(span.getDurationMs());
        assertTrue(span.getAttributes().contains("\"engine\":\"hybrid\""));
        assertEquals(rootSpanId, TraceContext.current().spanId(), "span 关闭后必须把上下文还给父级");
    }

    @Test
    void failedSpanKeepsBusinessErrorCodeAndRethrows() {
        when(runMapper.selectOne(any())).thenReturn(null);
        service.beginTrace("classic_document", "doc_1", "system", null);

        assertThrows(BizException.class, () -> service.span("llm.extract", "model", "llm", () -> {
            throw BizException.badRequest("分段超长");
        }));

        ArgumentCaptor<TraceSpanEntity> captor = ArgumentCaptor.forClass(TraceSpanEntity.class);
        verify(spanMapper).insert(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
        assertEquals("BAD_REQUEST", captor.getValue().getErrorCode());
        assertEquals("分段超长", captor.getValue().getErrorMessage());
    }

    @Test
    void finishTraceClosesRootSpanAndRun() {
        when(runMapper.selectOne(any())).thenReturn(null);
        service.beginTrace("classic_document", "doc_1", "system", null);

        service.finishTrace("FAILED", "PIPELINE_FAILED", "归一化失败");

        ArgumentCaptor<TraceSpanEntity> spanCaptor = ArgumentCaptor.forClass(TraceSpanEntity.class);
        verify(spanMapper).insert(spanCaptor.capture());
        assertEquals("root", spanCaptor.getValue().getName());
        assertEquals("FAILED", spanCaptor.getValue().getStatus());
        assertNotNull(spanCaptor.getValue().getDurationMs());

        ArgumentCaptor<TraceRunEntity> runCaptor = ArgumentCaptor.forClass(TraceRunEntity.class);
        verify(runMapper).updateById(runCaptor.capture());
        assertEquals("FAILED", runCaptor.getValue().getStatus());
        assertEquals("PIPELINE_FAILED", runCaptor.getValue().getErrorCode());
        assertNull(TraceContext.current(), "收尾后必须清掉上下文，防止线程复用串轨迹");
    }

    @Test
    void businessStatusIsNormalizedButKeptAsOutcome() {
        when(runMapper.selectOne(any())).thenReturn(null);
        service.beginTrace("qa_ask", "doc_1", "admin", null);

        service.finishTrace("INSUFFICIENT_EVIDENCE");

        ArgumentCaptor<TraceRunEntity> captor = ArgumentCaptor.forClass(TraceRunEntity.class);
        verify(runMapper).updateById(captor.capture());
        assertEquals("SUCCEEDED", captor.getValue().getStatus(), "证据不足是流程跑完了，不该算失败");
        assertEquals("INSUFFICIENT_EVIDENCE", captor.getValue().getOutcome(), "原生词要留档，否则看轨迹不知道当时怎么回事");
    }

    @Test
    void normalizeStatusCoversTheVocabulary() {
        assertEquals("SUCCEEDED", TracingService.normalizeStatus("OK"));
        assertEquals("SUCCEEDED", TracingService.normalizeStatus("SUCCEEDED"));
        assertEquals("SUCCEEDED", TracingService.normalizeStatus(null));
        assertEquals("SUCCEEDED", TracingService.normalizeStatus("  "));
        assertEquals("FAILED", TracingService.normalizeStatus("FAILED"));
        assertEquals("FAILED", TracingService.normalizeStatus("error"));
        assertEquals("INTERRUPTED", TracingService.normalizeStatus("CANCELLED"));
        assertEquals("SUCCEEDED", TracingService.normalizeStatus("PARTIAL"), "未见过的词按成功折算，避免新业务状态把失败率打高");
    }

    @Test
    void disablingTracingTurnsEverythingIntoNoop() {
        properties.getTracing().setEnabled(false);

        assertNull(service.beginTrace("qa_ask", "doc_1", "admin", null));
        assertEquals("hit", service.span("x", "model", "llm", () -> "hit"));
        service.event("whatever", Map.of("k", "v"));
        service.finishTrace("OK");

        verify(runMapper, never()).insert(any(com.kangheng.healthos.model.tracing.TraceRunEntity.class));
        verify(spanMapper, never()).insert(any(com.kangheng.healthos.model.tracing.TraceSpanEntity.class));
        verify(eventMapper, never()).insert(any(com.kangheng.healthos.model.tracing.TraceEventEntity.class));
    }

    @Test
    void eventsLandOnCurrentSpan() {
        when(runMapper.selectOne(any())).thenReturn(null);
        service.beginTrace("qa_ask", "doc_1", "admin", null);

        service.event("qa_started", Map.of("mode", "parallel"));

        ArgumentCaptor<TraceEventEntity> captor = ArgumentCaptor.forClass(TraceEventEntity.class);
        verify(eventMapper).insert(captor.capture());
        assertEquals("qa_started", captor.getValue().getName());
        assertEquals(TraceContext.current().spanId(), captor.getValue().getSpanId());
    }
}
