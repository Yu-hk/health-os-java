package com.kangheng.healthos.common;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import com.kangheng.healthos.common.AccessControl;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.mapper.patient.PatientBatchMapper;
import com.kangheng.healthos.mapper.patient.PatientBatchRowMapper;
import com.kangheng.healthos.mapper.patient.PatientDeletionResourceMapper;
import com.kangheng.healthos.mapper.care.CareAuditMapper;
import com.kangheng.healthos.mapper.care.CareCheckinMapper;
import com.kangheng.healthos.mapper.care.CareDocumentMapper;
import com.kangheng.healthos.mapper.care.CarePatientStateMapper;
import com.kangheng.healthos.mapper.care.CareRevisionMapper;
import com.kangheng.healthos.mapper.care.CareSignatureMapper;
import com.kangheng.healthos.model.care.CareDocument;
import com.kangheng.healthos.service.care.CareService;
import com.kangheng.healthos.provider.care.RuleBasedCareSectionGenerator;
import com.kangheng.healthos.service.outbox.OutboxService;
import com.kangheng.healthos.mapper.patient.PatientDeletionTaskMapper;
import com.kangheng.healthos.mapper.patient.PatientIntakeExamMapper;
import com.kangheng.healthos.mapper.patient.PatientIntakePartMapper;
import com.kangheng.healthos.mapper.patient.PatientMapper;
import com.kangheng.healthos.mapper.patient.PatientMetricMapper;
import com.kangheng.healthos.mapper.patient.PatientObjectMapper;
import com.kangheng.healthos.mapper.patient.PatientRevisionMapper;
import com.kangheng.healthos.model.patient.Patient;
import com.kangheng.healthos.model.patient.PatientBatch;
import com.kangheng.healthos.model.patient.PatientDeletionTask;
import com.kangheng.healthos.model.patient.PatientIntakeExam;
import com.kangheng.healthos.model.patient.PatientIntakePart;
import com.kangheng.healthos.model.patient.PatientObjectEntity;
import com.kangheng.healthos.provider.patient.RuleBasedPatientRecordExtractor;
import com.kangheng.healthos.service.patient.PatientBatchService;
import com.kangheng.healthos.service.patient.PatientDeletionService;
import com.kangheng.healthos.service.patient.PatientIntakeService;
import com.kangheng.healthos.service.patient.PatientLifecycleService;
import com.kangheng.healthos.service.patient.PatientObjectService;
import com.kangheng.healthos.service.patient.PatientService;
import com.kangheng.healthos.mapper.qa.QaConversationMapper;
import com.kangheng.healthos.mapper.qa.QaMessageMapper;
import com.kangheng.healthos.model.qa.QaConversation;
import com.kangheng.healthos.service.qa.QaService;
import com.kangheng.healthos.service.tracing.TraceQueryService;
import com.kangheng.healthos.mapper.tracing.TraceEventMapper;
import com.kangheng.healthos.mapper.tracing.TraceRunMapper;
import com.kangheng.healthos.mapper.tracing.TraceSpanMapper;
import com.kangheng.healthos.model.tracing.TraceRunEntity;
import com.kangheng.healthos.spi.storage.ObjectStorage;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.kangheng.healthos.spi.ai.LlmProvider;
import com.kangheng.healthos.mapper.classics.ClassicDocumentMapper;
import com.kangheng.healthos.service.classics.DocumentNormalizer;
import com.kangheng.healthos.service.graph.GraphService;
import com.kangheng.healthos.service.qa.BranchOrchestrator;
import com.kangheng.healthos.spi.retrieval.KnowledgeBase;
import com.kangheng.healthos.service.tracing.TracingService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * owner 隔离的回归测试。
 *
 * <p>这一组用例守的是"越权读"：患者档案、指标、附件、批次、删除台账、问答会话、
 * 观测轨迹，任何一项漏掉 owner 条件都是数据泄露。
 *
 * <p>断言方式不是"调用没报错"，而是**检查真正下发的 WHERE 子句**里有没有 owner——
 * 否则"忘了加过滤条件"这种错误在单测里完全看不出来。
 */
class OwnerIsolationTest {

    private HealthOsProperties properties;

    @BeforeAll
    static void initMybatisMeta() {
        // 让 lambda 条件能在单测里渲染成 SQL，否则看不到真实 WHERE
        initMeta(Patient.class);
        initMeta(PatientObjectEntity.class);
        initMeta(PatientBatch.class);
        initMeta(PatientDeletionTask.class);
        initMeta(CareDocument.class);
        initMeta(PatientIntakeExam.class);
        initMeta(PatientIntakePart.class);
        initMeta(TraceRunEntity.class);
    }

    private static void initMeta(Class<?> entity) {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
    }

    /** 白名单从配置取值、内核只认名单——与 InfraConfig 的装配方式保持一致。 */
    private AccessControl accessControl() {
        return new AccessControl(properties.getSecurity().getAdminUsers(),
                properties.getSecurity().getTraceGlobalUsers());
    }

    @BeforeEach
    void setUp() {
        properties = new HealthOsProperties();
        properties.getSecurity().setAdminUsers(List.of("admin"));
        properties.getSecurity().setTraceGlobalUsers(List.of("admin"));
    }

    // ------------------------------------------------------------ 白名单判定

    @Test
    @DisplayName("白名单：只有名单里的账号才是运维/全局读")
    void whitelistIsExplicit() {
        AccessControl control = accessControl();

        assertThat(control.isAdmin("admin")).isTrue();
        assertThat(control.isAdmin("doctor")).isFalse();
        assertThat(control.isTraceGlobalReader("admin")).isTrue();
        assertThat(control.isTraceGlobalReader("doctor")).isFalse();
        assertThat(control.canRead("doctor", "doctor")).isTrue();
        assertThat(control.canRead("doctor", "other")).isFalse();
    }

    @Test
    @DisplayName("运维闸门：非运维账号 403")
    void nonAdminIsRejected() {
        AccessControl control = accessControl();

        assertThatThrownBy(() -> control.requireAdmin("doctor"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getStatus().value())
                .isEqualTo(403);
    }

    // ------------------------------------------------------------ 患者域

    @Test
    @DisplayName("患者列表按归属人过滤，不会返回别人的档案")
    void patientListIsScopedToOwner() {
        PatientMapper patientMapper = mock(PatientMapper.class);
        PatientMetricMapper metricMapper = mock(PatientMetricMapper.class);
        PatientLifecycleService lifecycle = mock(PatientLifecycleService.class);
        PatientService service = new PatientService(patientMapper, metricMapper, lifecycle);
        when(patientMapper.selectPage(any(), any())).thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

        service.list(1, 20, "doctor");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Patient>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(patientMapper).selectPage(any(), captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("created_by");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("doctor");
    }

    @Test
    @DisplayName("附件清单按归属人过滤")
    void objectListIsScopedToOwner() {
        PatientObjectMapper objectMapper = mock(PatientObjectMapper.class);
        PatientLifecycleService lifecycle = mock(PatientLifecycleService.class);
        PatientObjectService service = new PatientObjectService(objectMapper, lifecycle,
                mock(ObjectStorage.class), properties);
        when(objectMapper.selectList(any())).thenReturn(List.of());

        service.list("pat_1", "doctor");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<PatientObjectEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(objectMapper).selectList(captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("owner");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("doctor");
    }

    @Test
    @DisplayName("批次幂等查重限定在归属人内：A 传过的文件，B 再传不会拿到 A 的批次")
    void batchIdempotencyIsScopedToOwner() {
        PatientBatchMapper batchMapper = mock(PatientBatchMapper.class);
        PatientBatchRowMapper rowMapper = mock(PatientBatchRowMapper.class);
        PatientLifecycleService lifecycle = mock(PatientLifecycleService.class);
        PatientBatchService service = new PatientBatchService(batchMapper, rowMapper, lifecycle,
                mock(com.kangheng.healthos.service.outbox.OutboxService.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class), properties);
        when(batchMapper.selectOne(any())).thenReturn(null);

        byte[] csv = "name,gender\n张三,MALE\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        service.submit("b.csv", csv, "doctor");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<PatientBatch>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(batchMapper).selectOne(captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("created_by");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("doctor");
    }

    @Test
    @DisplayName("删除任务按归属人过滤：别人的任务 404")
    void deletionTaskIsScopedToOwner() {
        PatientDeletionTaskMapper taskMapper = mock(PatientDeletionTaskMapper.class);
        PatientDeletionService service = new PatientDeletionService(taskMapper,
                mock(PatientDeletionResourceMapper.class), mock(PatientMapper.class),
                mock(PatientMetricMapper.class), mock(PatientRevisionMapper.class),
                mock(PatientObjectMapper.class), mock(PatientBatchRowMapper.class),
                mock(PatientLifecycleService.class),
                mock(com.kangheng.healthos.service.outbox.OutboxService.class), mock(ObjectStorage.class));
        // 按 owner 过滤后查不到
        when(taskMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.view("pdt_1", "mallory"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getStatus().value())
                .isEqualTo(404);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<PatientDeletionTask>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskMapper).selectOne(captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("owner");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("mallory");
    }

    // ------------------------------------------------------------ 资料录入

    @Test
    @DisplayName("录入单详情按归属人过滤：别人的录入单 404")
    void intakeExamIsScopedToOwner() {
        PatientIntakeExamMapper examMapper = mock(PatientIntakeExamMapper.class);
        PatientIntakeService service = intakeService(examMapper);
        when(examMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.detail("pie_1", "mallory"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getStatus().value())
                .isEqualTo(404);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<PatientIntakeExam>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(examMapper).selectOne(captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?> ) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("owner");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("mallory");
    }

    @Test
    @DisplayName("录入单列表按归属人过滤")
    void intakeListIsScopedToOwner() {
        PatientIntakeExamMapper examMapper = mock(PatientIntakeExamMapper.class);
        PatientIntakeService service = intakeService(examMapper);
        when(examMapper.selectPage(any(), any()))
                .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

        service.list("doctor", 1, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<PatientIntakeExam>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(examMapper).selectPage(any(), captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?> ) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("owner");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("doctor");
    }

    @Test
    @DisplayName("录入分片按归属人过滤：查别人的分片读不到")
    void intakePartIsScopedToOwner() {
        PatientIntakeExamMapper examMapper = mock(PatientIntakeExamMapper.class);
        PatientIntakePartMapper partMapper = mock(PatientIntakePartMapper.class);
        PatientIntakeService service = intakeService(examMapper, partMapper);

        PatientIntakeExam exam = new PatientIntakeExam();
        exam.setId("pie_1");
        exam.setOwner("doctor");
        exam.setStatus("EXTRACTED");
        exam.setBasicReviewStatus("CONFIRMED");
        exam.setClinicalReviewStatus("CONFIRMED");
        when(examMapper.selectOne(any())).thenReturn(exam);
        when(partMapper.selectList(any())).thenReturn(List.of());

        service.detail("pie_1", "doctor");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<PatientIntakePart>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(partMapper).selectList(captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?> ) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("owner");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("doctor");
    }

    @Test
    @DisplayName("录入幂等查重限定在归属人内：A 传过的资料，B 再传不会复用 A 的录入单")
    void intakeIdempotencyIsScopedToOwner() {
        PatientIntakeExamMapper examMapper = mock(PatientIntakeExamMapper.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.putAt(anyString(), any())).thenReturn(
                new ObjectStorage.StoredObject("patient-intake/doctor/pie_1.txt", "sha", 12));
        PatientIntakeService service = intakeService(examMapper, mock(PatientIntakePartMapper.class),
                storage);
        when(examMapper.selectOne(any())).thenReturn(null);

        service.submit("r.txt", "text/plain", "姓名：张三".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "doctor");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<PatientIntakeExam>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(examMapper).selectOne(captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?> ) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("owner");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("doctor");
    }

    private PatientIntakeService intakeService(PatientIntakeExamMapper examMapper) {
        return intakeService(examMapper, mock(PatientIntakePartMapper.class), mock(ObjectStorage.class));
    }

    private PatientIntakeService intakeService(PatientIntakeExamMapper examMapper,
                                              PatientIntakePartMapper partMapper) {
        return intakeService(examMapper, partMapper, mock(ObjectStorage.class));
    }

    private PatientIntakeService intakeService(PatientIntakeExamMapper examMapper,
                                              PatientIntakePartMapper partMapper,
                                              ObjectStorage storage) {
        return new PatientIntakeService(examMapper, partMapper, mock(PatientLifecycleService.class),
                mock(PatientService.class), new RuleBasedPatientRecordExtractor(),
                mock(com.kangheng.healthos.service.classics.DocumentNormalizer.class),
                storage, mock(com.kangheng.healthos.service.outbox.OutboxService.class),
                properties);
    }

    // ------------------------------------------------------------ 健康管理

    @Test
    @DisplayName("健康管理文档按归属人过滤：查别人的文档只能得到 404")
    void careDocumentIsScopedToOwner() {
        CareDocumentMapper documentMapper = mock(CareDocumentMapper.class);
        CareService careService = careService(documentMapper);

        CareDocument document = new CareDocument();
        document.setId("cdoc_admin");
        document.setOwner("admin");
        document.setKind(CareService.KIND_REPORT);
        document.setPatientId("pat_1");
        document.setStatus(CareService.STATUS_DRAFT);
        document.setRevision(1);
        document.setSections("{}");
        document.setAggregate("{}");
        when(documentMapper.selectById("cdoc_admin")).thenReturn(document);

        assertThatThrownBy(() -> careService.get("doctor", "cdoc_admin"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("care 文档列表必须带 owner 条件：不能只按 kind 查全部")
    void careListQueryCarriesOwner() {
        CareDocumentMapper documentMapper = mock(CareDocumentMapper.class);
        CareService careService = careService(documentMapper);
        when(documentMapper.selectList(any())).thenReturn(List.of());

        careService.list("doctor", CareService.KIND_REPORT, null, 10);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<CareDocument>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(documentMapper).selectList(captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("owner");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("doctor");
    }

    private CareService careService(CareDocumentMapper documentMapper) {
        return new CareService(documentMapper, mock(CareRevisionMapper.class),
                mock(CareAuditMapper.class), mock(CareSignatureMapper.class),
                mock(CarePatientStateMapper.class), mock(CareCheckinMapper.class),
                mock(PatientIntakeExamMapper.class), mock(PatientMapper.class),
                mock(PatientMetricMapper.class),
                new RuleBasedCareSectionGenerator(), mock(OutboxService.class), properties);
    }

    // ------------------------------------------------------------ 问答

    @Test
    @DisplayName("问答会话按归属人过滤：别人的会话读不到")
    void qaConversationIsScopedToOwner() {
        QaConversationMapper conversationMapper = mock(QaConversationMapper.class);
        QaMessageMapper messageMapper = mock(QaMessageMapper.class);
        QaService service = new QaService(conversationMapper, messageMapper,
                mock(com.kangheng.healthos.mapper.classics.ClassicDocumentMapper.class),
                mock(com.kangheng.healthos.spi.retrieval.KnowledgeBase.class),
                mock(com.kangheng.healthos.spi.ai.LlmProvider.class), properties,
                mock(com.kangheng.healthos.service.qa.BranchOrchestrator.class),
                mock(com.kangheng.healthos.service.graph.GraphService.class),
                mock(PatientMetricMapper.class),
                mock(com.kangheng.healthos.service.tracing.TracingService.class),
                mock(com.kangheng.healthos.service.qa.KnowledgeBindingService.class));

        QaConversation others = new QaConversation();
        others.setId("qcv_1");
        others.setDocumentId("doc_1");
        others.setCreatedBy("admin");
        when(conversationMapper.selectById("qcv_1")).thenReturn(others);

        assertThatThrownBy(() -> service.messages("qcv_1", "doctor"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getStatus().value())
                .isEqualTo(404);
        verify(messageMapper, never()).selectList(any());
    }

    // ------------------------------------------------------------ 观测

    @Test
    @DisplayName("轨迹列表默认只看得到自己的，非全局读者不能查他人")
    void traceListIsScopedToOwner() {
        TraceRunMapper runMapper = mock(TraceRunMapper.class);
        TraceQueryService service = new TraceQueryService(runMapper, mock(TraceSpanMapper.class),
                mock(TraceEventMapper.class), mock(com.kangheng.healthos.service.tracing.TracingService.class),
                properties, accessControl());
        when(runMapper.selectPage(any(), any()))
                .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

        service.list(null, null, null, null, null, null, 1, 20, "doctor");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<TraceRunEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(runMapper).selectPage(any(), captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("owner");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("doctor");
    }

    @Test
    @DisplayName("非全局读者显式查他人轨迹：403")
    void crossOwnerTraceListIsRejected() {
        TraceRunMapper runMapper = mock(TraceRunMapper.class);
        TraceQueryService service = new TraceQueryService(runMapper, mock(TraceSpanMapper.class),
                mock(TraceEventMapper.class), mock(com.kangheng.healthos.service.tracing.TracingService.class),
                properties, accessControl());

        assertThatThrownBy(() -> service.list(null, null, "admin", null, null, null, 1, 20, "doctor"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo("TRACE_SCOPE_FORBIDDEN");
    }

    @Test
    @DisplayName("轨迹详情越权：404（不泄露轨迹是否存在）")
    void traceDetailOfOthersIsNotFound() {
        TraceRunMapper runMapper = mock(TraceRunMapper.class);
        TraceQueryService service = new TraceQueryService(runMapper, mock(TraceSpanMapper.class),
                mock(TraceEventMapper.class), mock(com.kangheng.healthos.service.tracing.TracingService.class),
                properties, accessControl());

        TraceRunEntity run = new TraceRunEntity();
        run.setTraceId("t1");
        run.setOwner("admin");
        run.setStatus("SUCCEEDED");
        run.setAcceptedAt(OffsetDateTime.now());
        when(runMapper.selectById("t1")).thenReturn(run);

        assertThatThrownBy(() -> service.detail("t1", "doctor"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getStatus().value())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("全局读白名单里的人能看别人的轨迹")
    void traceGlobalReaderCanSeeOthers() {
        TraceRunMapper runMapper = mock(TraceRunMapper.class);
        TraceSpanMapper spanMapper = mock(TraceSpanMapper.class);
        TraceQueryService service = new TraceQueryService(runMapper, spanMapper,
                mock(TraceEventMapper.class), mock(com.kangheng.healthos.service.tracing.TracingService.class),
                properties, accessControl());

        TraceRunEntity run = new TraceRunEntity();
        run.setTraceId("t1");
        run.setOwner("doctor");
        run.setStatus("SUCCEEDED");
        run.setAcceptedAt(OffsetDateTime.now());
        when(runMapper.selectById("t1")).thenReturn(run);
        when(spanMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.detail("t1", "admin").trace().traceId()).isEqualTo("t1");
    }
}
