package com.kangheng.healthos.service.care;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import com.kangheng.healthos.contract.care.CareContract;
import com.kangheng.healthos.mapper.care.CareAuditMapper;
import com.kangheng.healthos.mapper.care.CareCheckinMapper;
import com.kangheng.healthos.mapper.care.CareDocumentMapper;
import com.kangheng.healthos.mapper.care.CarePatientStateMapper;
import com.kangheng.healthos.mapper.care.CareRevisionMapper;
import com.kangheng.healthos.mapper.care.CareSignatureMapper;
import com.kangheng.healthos.model.care.CareAudit;
import com.kangheng.healthos.model.care.CareCheckin;
import com.kangheng.healthos.model.care.CareDocument;
import com.kangheng.healthos.model.care.CarePatientState;
import com.kangheng.healthos.model.care.CareRevision;
import com.kangheng.healthos.model.care.CareSignature;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.service.outbox.OutboxService;
import com.kangheng.healthos.mapper.patient.PatientIntakeExamMapper;
import com.kangheng.healthos.mapper.patient.PatientMapper;
import com.kangheng.healthos.mapper.patient.PatientMetricMapper;
import com.kangheng.healthos.model.patient.Patient;
import com.kangheng.healthos.model.patient.PatientIntakeExam;
import com.kangheng.healthos.model.patient.PatientMetric;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.kangheng.healthos.provider.care.RuleBasedCareSectionGenerator;
import com.kangheng.healthos.common.Ids;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 健康管理的流程约束。
 *
 * <p>这一组用例守的是<b>签发与打卡的安全边界</b>：
 * <ul>
 *   <li>没有签发权限的人不能签；</li>
 *   <li>审核意见太短（等于没审核）不能签；</li>
 *   <li>未确认的体检不能生成报告；</li>
 *   <li>内容没生成完不能签发；</li>
 *   <li>只有当前生效的计划能打卡，且任务必须真实存在；</li>
 *   <li>越权访问一律 404 而不是 403。</li>
 * </ul>
 * 每一条都对应一个真实会出事的场景，不是形式化检查。
 */
class CareServiceTest {

    private CareDocumentMapper documentMapper;
    private CareRevisionMapper revisionMapper;
    private CareAuditMapper auditMapper;
    private CareSignatureMapper signatureMapper;
    private CarePatientStateMapper stateMapper;
    private CareCheckinMapper checkinMapper;
    private PatientIntakeExamMapper intakeMapper;
    private PatientMapper patientMapper;
    private PatientMetricMapper metricMapper;
    private OutboxService outboxService;
    private HealthOsProperties properties;
    private CareService service;

    @BeforeAll
    static void initMeta() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                CareDocument.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                CareRevision.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                CareAudit.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                CareSignature.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                CarePatientState.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                CareCheckin.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                PatientMetric.class);
    }

    @BeforeEach
    void setUp() {
        documentMapper = mock(CareDocumentMapper.class);
        revisionMapper = mock(CareRevisionMapper.class);
        auditMapper = mock(CareAuditMapper.class);
        signatureMapper = mock(CareSignatureMapper.class);
        stateMapper = mock(CarePatientStateMapper.class);
        checkinMapper = mock(CareCheckinMapper.class);
        intakeMapper = mock(PatientIntakeExamMapper.class);
        patientMapper = mock(PatientMapper.class);
        metricMapper = mock(PatientMetricMapper.class);
        outboxService = mock(OutboxService.class);
        properties = new HealthOsProperties();
        properties.getCare().setSignerUsers(List.of("doctor"));
        service = new CareService(documentMapper, revisionMapper, auditMapper, signatureMapper,
                stateMapper, checkinMapper, intakeMapper, patientMapper, metricMapper,
                new RuleBasedCareSectionGenerator(), outboxService, properties);
    }

    // ------------------------------------------------------------ 创建报告

    @Test
    @DisplayName("体检未确认就不能生成报告：没人工核查过的数据不能进报告")
    void refusesExamThatIsNotConfirmed() {
        PatientIntakeExam exam = exam();
        exam.setStatus("EXTRACTED");
        when(intakeMapper.selectById("pie_1")).thenReturn(exam);

        assertThatThrownBy(() -> service.createReport("doctor",
                new CareContract.CreateReportRequest("pie_1", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已确认");
        verify(documentMapper, never()).insert(any(CareDocument.class));
    }

    @Test
    @DisplayName("别人的体检不能拿来生成报告")
    void refusesExamOwnedByAnotherUser() {
        PatientIntakeExam exam = exam();
        exam.setOwner("admin");
        when(intakeMapper.selectById("pie_1")).thenReturn(exam);

        assertThatThrownBy(() -> service.createReport("doctor",
                new CareContract.CreateReportRequest("pie_1", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("体检记录");
    }

    @Test
    @DisplayName("同一幂等键重复提交返回同一份文档，不重复生成")
    void reusesDocumentForSameIdempotencyKey() {
        PatientIntakeExam exam = exam();
        when(intakeMapper.selectById("pie_1")).thenReturn(exam);
        when(patientMapper.selectById("pat_1")).thenReturn(patient());
        when(documentMapper.selectOne(any())).thenReturn(existingReport());

        CareContract.AcceptedResponse response = service.createReport("doctor",
                new CareContract.CreateReportRequest("pie_1", "key-1"));

        assertThat(response.reused()).isTrue();
        assertThat(response.documentId()).isEqualTo("cdoc_existing");
        verify(documentMapper, never()).insert(any(CareDocument.class));
    }

    @Test
    @DisplayName("同一幂等键换了体检资料要报错，不能静默复用旧文档")
    void refusesIdempotencyKeyReusedWithDifferentInput() {
        PatientIntakeExam exam = exam();
        when(intakeMapper.selectById("pie_1")).thenReturn(exam);
        when(patientMapper.selectById("pat_1")).thenReturn(patient());
        CareDocument existing = existingReport();
        existing.setInputFingerprint("different-fingerprint");
        when(documentMapper.selectOne(any())).thenReturn(existing);

        assertThatThrownBy(() -> service.createReport("doctor",
                new CareContract.CreateReportRequest("pie_1", "key-1")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("幂等键");
    }

    // ------------------------------------------------------------ 端到端

    @Test
    @DisplayName("端到端：生成报告草稿 → 异步生成内容 → 签发 → 同步落计划草稿")
    void reportDraftToSignedWithPlan() {
        PatientIntakeExam exam = exam();
        when(intakeMapper.selectById("pie_1")).thenReturn(exam);
        when(patientMapper.selectById("pat_1")).thenReturn(patient());
        when(stateMapper.selectById("pat_1")).thenReturn(state());
        when(documentMapper.selectOne(any())).thenReturn(null);
        when(documentMapper.insert(any(CareDocument.class))).thenAnswer(invocation -> 1);
        when(metricMapper.selectList(any())).thenReturn(List.of());

        // ① 创建草稿：冻结来源快照、记幂等指纹、排队生成
        CareContract.AcceptedResponse accepted = service.createReport("doctor",
                new CareContract.CreateReportRequest("pie_1", "key-e2e"));
        assertThat(accepted.reused()).isFalse();

        ArgumentCaptor<CareDocument> insertCaptor = ArgumentCaptor.forClass(CareDocument.class);
        verify(documentMapper).insert(insertCaptor.capture());
        CareDocument draft = insertCaptor.getValue();
        assertThat(draft.getKind()).isEqualTo(CareService.KIND_REPORT);
        assertThat(draft.getStatus()).isEqualTo(CareService.STATUS_DRAFT);
        assertThat(draft.getRevision()).isEqualTo(1);
        assertThat(draft.getSourceExamId()).isEqualTo("pie_1");
        assertThat(draft.getInputFingerprint()).isNotBlank();
        assertThat(draft.getSourceSnapshot())
                .as("来源快照要在生成之前就冻结：体检数据之后会变，报告不能跟着变")
                .contains("张三");
        verify(outboxService).append(eq("care_document"), eq(draft.getId()),
                eq(CareService.EVENT_GENERATE), any());

        // ② 异步生成：分节内容落到文档上
        when(documentMapper.selectById(draft.getId())).thenReturn(draft);
        service.generate(draft.getId());
        assertThat(Json.readMap(draft.getSections()))
                .as("报告的每个分节都要生成出来，缺一节就不能签")
                .containsKeys(CareContract.REPORT_KEYS.toArray(new String[0]));
        assertThat(Json.readMap(draft.getAggregate()))
                .containsEntry("complete", true);

        // ③ 签发：留签名、同步生成计划草稿、把报告挂为当前生效
        when(signatureMapper.selectOne(any())).thenReturn(null);
        CareContract.DocumentView signed = service.sign("doctor", draft.getId(),
                new CareContract.SignRequest(1, "内容核对无误，可以签发", "2026-11-10"));
        assertThat(signed.status()).isEqualTo(CareService.STATUS_SIGNED);
        assertThat(draft.getSignedRevision()).isNotNull();

        ArgumentCaptor<CareSignature> signatureCaptor = ArgumentCaptor.forClass(CareSignature.class);
        verify(signatureMapper).insert(signatureCaptor.capture());
        assertThat(signatureCaptor.getValue().getPlanDocumentId()).isNotBlank();

        ArgumentCaptor<CareDocument> planCaptor = ArgumentCaptor.forClass(CareDocument.class);
        verify(documentMapper, org.mockito.Mockito.times(2)).insert(planCaptor.capture());
        CareDocument plan = planCaptor.getAllValues().get(1);
        assertThat(plan.getKind()).isEqualTo(CareService.KIND_PLAN);
        assertThat(plan.getStatus()).isEqualTo(CareService.STATUS_DRAFT);
        assertThat(plan.getSourceReportId()).isEqualTo(draft.getId());
        assertThat(plan.getSourceReportRevision()).isEqualTo(draft.getSignedRevision());

        ArgumentCaptor<CarePatientState> stateCaptor = ArgumentCaptor.forClass(CarePatientState.class);
        verify(stateMapper).updateById(stateCaptor.capture());
        assertThat(stateCaptor.getValue().getActiveReportId()).isEqualTo(draft.getId());
    }

    @Test
    @DisplayName("发布基线必须随草稿一起入库，否则第二次签发永远被基线校验挡下")
    void publicationBaselineIsPersistedWithDraft() {
        PatientIntakeExam exam = exam();
        CarePatientState activeState = state();
        activeState.setActiveReportId("cdoc_prev");
        when(intakeMapper.selectById("pie_1")).thenReturn(exam);
        when(patientMapper.selectById("pat_1")).thenReturn(patient());
        when(stateMapper.selectById("pat_1")).thenReturn(activeState);
        when(documentMapper.selectOne(any())).thenReturn(null);
        when(documentMapper.insert(any(CareDocument.class))).thenAnswer(invocation -> 1);

        service.createReport("doctor", new CareContract.CreateReportRequest("pie_1", "key-baseline"));

        ArgumentCaptor<CareDocument> captor = ArgumentCaptor.forClass(CareDocument.class);
        verify(documentMapper).insert(captor.capture());
        assertThat(captor.getValue().getParentDocumentId())
                .as("基线要在 insert 之前定下来：放在 insert 之后赋值，库里存的是 null，"
                        + "发布守卫会永远判定\"基线已变\"")
                .isEqualTo("cdoc_prev");
    }

    // ------------------------------------------------------------ 签发

    @Test
    @DisplayName("没有签发权限的账号不能签发")
    void refusesSignerWithoutPermission() {
        when(documentMapper.selectById("cdoc_1")).thenReturn(draftReport());

        assertThatThrownBy(() -> service.sign("admin", "cdoc_1",
                new CareContract.SignRequest(1, "内容核对无误，可以签发", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("签发权限");
        verify(signatureMapper, never()).insert(any(CareSignature.class));
    }

    @Test
    @DisplayName("审核意见太短等于没审核，不许签发")
    void refusesSignatureWithTooShortComment() {
        when(documentMapper.selectById("cdoc_1")).thenReturn(draftReport());

        assertThatThrownBy(() -> service.sign("doctor", "cdoc_1",
                new CareContract.SignRequest(1, "好", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("审核意见");
        verify(signatureMapper, never()).insert(any(CareSignature.class));
    }

    @Test
    @DisplayName("审核意见全是空格/标点也算无效——有效字符要够 5 个")
    void refusesSignatureWithBlankComment() {
        when(documentMapper.selectById("cdoc_1")).thenReturn(draftReport());

        assertThatThrownBy(() -> service.sign("doctor", "cdoc_1",
                new CareContract.SignRequest(1, "     。。  ", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("审核意见");
    }

    @Test
    @DisplayName("版本不匹配时拒绝签发：不能让后提交的人覆盖先提交的")
    void refusesSignatureWithStaleRevision() {
        when(documentMapper.selectById("cdoc_1")).thenReturn(draftReport());

        assertThatThrownBy(() -> service.sign("doctor", "cdoc_1",
                new CareContract.SignRequest(99, "内容核对无误，可以签发", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("版本冲突");
    }

    @Test
    @DisplayName("内容没生成完不能签发：空壳报告签了也没意义")
    void refusesSignatureOfIncompleteDocument() {
        CareDocument document = draftReport();
        document.setAggregate(Json.write(Map.of("complete", false)));
        when(documentMapper.selectById("cdoc_1")).thenReturn(document);

        assertThatThrownBy(() -> service.sign("doctor", "cdoc_1",
                new CareContract.SignRequest(1, "内容核对无误，可以签发", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("生成完成");
    }

    @Test
    @DisplayName("签发成功后同步生成计划草稿，并把报告挂为当前生效")
    void signCreatesPlanAndUpdatesState() {
        CareDocument document = draftReport();
        when(documentMapper.selectById("cdoc_1")).thenReturn(document);
        when(signatureMapper.selectOne(any())).thenReturn(null);
        when(stateMapper.selectById("pat_1")).thenReturn(state());
        when(documentMapper.insert(any(CareDocument.class))).thenAnswer(invocation -> 1);

        service.sign("doctor", "cdoc_1",
                new CareContract.SignRequest(1, "内容核对无误，可以签发", "2026-11-10"));

        assertThat(document.getStatus()).isEqualTo(CareService.STATUS_SIGNED);
        assertThat(document.getSignedRevision()).isNotNull();

        ArgumentCaptor<CareSignature> signatureCaptor = ArgumentCaptor.forClass(CareSignature.class);
        verify(signatureMapper).insert(signatureCaptor.capture());
        assertThat(signatureCaptor.getValue().getSigner()).isEqualTo("doctor");
        assertThat(signatureCaptor.getValue().getPlanDocumentId()).isNotNull();
        assertThat(signatureCaptor.getValue().getKind())
                .isEqualTo("authenticated_identity_confirmation");

        ArgumentCaptor<CarePatientState> stateCaptor = ArgumentCaptor.forClass(CarePatientState.class);
        verify(stateMapper).updateById(stateCaptor.capture());
        assertThat(stateCaptor.getValue().getActiveReportId()).isEqualTo("cdoc_1");
    }

    @Test
    @DisplayName("签发后写入的发布修订快照必须反映 SIGNED 状态（不能停留在签发前的 DRAFT）")
    void publishedRevisionSnapshotReflectsSignedStatus() {
        CareDocument document = draftReport();
        when(documentMapper.selectById("cdoc_1")).thenReturn(document);
        when(signatureMapper.selectOne(any())).thenReturn(null);
        when(stateMapper.selectById("pat_1")).thenReturn(state());
        when(documentMapper.insert(any(CareDocument.class))).thenAnswer(i -> 1);

        service.sign("doctor", "cdoc_1",
                new CareContract.SignRequest(1, "内容核对无误，可以签发", "2026-11-10"));

        // 记忆采集只认 SIGNED 的发布修订；若快照 status 停留在签发前的 DRAFT，
        // 下游按快照 status 判定就会误拒。这条用例守住 snapshotRevision 重算聚合的修复。
        ArgumentCaptor<CareRevision> revCaptor = ArgumentCaptor.forClass(CareRevision.class);
        verify(revisionMapper).insert(revCaptor.capture());
        Map<String, Object> snapshot = Json.readMap(revCaptor.getValue().getSnapshot());
        assertThat(snapshot.get("status")).isEqualTo(CareService.STATUS_SIGNED);
    }

    @Test
    @DisplayName("同一份内容重复签发视为幂等重放；换了内容则明确拒绝")
    void refusesSecondSignatureWithDifferentContent() {
        CareDocument document = draftReport();
        when(documentMapper.selectById("cdoc_1")).thenReturn(document);
        CareSignature prior = new CareSignature();
        prior.setDocumentId("cdoc_1");
        prior.setExpectedRevision(1);
        prior.setReviewComment("内容核对无误，可以签发");
        prior.setContentHash("some-other-hash");
        when(signatureMapper.selectOne(any())).thenReturn(prior);

        assertThatThrownBy(() -> service.sign("doctor", "cdoc_1",
                new CareContract.SignRequest(1, "内容核对无误，可以签发", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已签发");
    }

    // ------------------------------------------------------------ 打卡

    @Test
    @DisplayName("未生效的计划不能打卡：草稿阶段还不能算执行")
    void refusesCheckinOnInactivePlan() {
        CareDocument plan = planDocument(CareService.STATUS_DRAFT);
        when(documentMapper.selectById("cdoc_plan")).thenReturn(plan);

        assertThatThrownBy(() -> service.checkin("doctor", "cdoc_plan", "task_week_1",
                new CareContract.CheckinRequest(true, 0)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("生效");
    }

    @Test
    @DisplayName("计划里不存在的任务不能打卡")
    void refusesCheckinOnUnknownTask() {
        CareDocument plan = planDocument(CareService.STATUS_ACTIVE);
        when(documentMapper.selectById("cdoc_plan")).thenReturn(plan);
        CarePatientState state = state();
        state.setActivePlanId("cdoc_plan");
        state.setActivePlanRevision(plan.getRevision());
        when(stateMapper.selectById("pat_1")).thenReturn(state);

        assertThatThrownBy(() -> service.checkin("doctor", "cdoc_plan", "task_not_exists",
                new CareContract.CheckinRequest(true, 0)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有此任务");
    }

    @Test
    @DisplayName("已被他人更新的打卡记录要明确报版本冲突，不能静默覆盖")
    void refusesCheckinWithStaleVersion() {
        CareDocument plan = planDocument(CareService.STATUS_ACTIVE);
        when(documentMapper.selectById("cdoc_plan")).thenReturn(plan);
        CarePatientState state = state();
        state.setActivePlanId("cdoc_plan");
        state.setActivePlanRevision(plan.getRevision());
        when(stateMapper.selectById("pat_1")).thenReturn(state);

        CareCheckin existing = new CareCheckin();
        existing.setId("cchk_1");
        existing.setDocumentId("cdoc_plan");
        existing.setTaskId("task_week_1");
        existing.setCompleted(false);
        existing.setVersion(3);
        when(checkinMapper.selectOne(any())).thenReturn(existing);

        assertThatThrownBy(() -> service.checkin("doctor", "cdoc_plan", "task_week_1",
                new CareContract.CheckinRequest(true, 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("版本");
    }

    @Test
    @DisplayName("打卡成功要留审计：谁在什么时候完成了哪个任务")
    void checkinWritesAuditTrail() {
        CareDocument plan = planDocument(CareService.STATUS_ACTIVE);
        when(documentMapper.selectById("cdoc_plan")).thenReturn(plan);
        CarePatientState state = state();
        state.setActivePlanId("cdoc_plan");
        state.setActivePlanRevision(plan.getRevision());
        when(stateMapper.selectById("pat_1")).thenReturn(state);
        when(checkinMapper.selectOne(any())).thenReturn(null);
        when(checkinMapper.selectList(any())).thenReturn(List.of());

        service.checkin("doctor", "cdoc_plan", "task_week_1",
                new CareContract.CheckinRequest(true, 0));

        ArgumentCaptor<CareAudit> auditCaptor = ArgumentCaptor.forClass(CareAudit.class);
        verify(auditMapper).insert(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getAction()).isEqualTo("task_completed");
        assertThat(auditCaptor.getValue().getActor()).isEqualTo("doctor");
    }

    // ------------------------------------------------------------ owner 隔离

    @Test
    @DisplayName("越权访问返回 404：403 等于承认这个文档存在")
    void hidesDocumentsOfOtherUsers() {
        CareDocument document = draftReport();
        document.setOwner("admin");
        when(documentMapper.selectById("cdoc_1")).thenReturn(document);

        assertThatThrownBy(() -> service.get("doctor", "cdoc_1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
    }

    // ------------------------------------------------------------ 辅助

    private PatientIntakeExam exam() {
        PatientIntakeExam exam = new PatientIntakeExam();
        exam.setId("pie_1");
        exam.setOwner("doctor");
        exam.setStatus("CONFIRMED");
        exam.setPatientId("pat_1");
        exam.setRevision(0);
        exam.setExtractor("rule-based");
        return exam;
    }

    private Patient patient() {
        Patient patient = new Patient();
        patient.setId("pat_1");
        patient.setName("张三");
        patient.setGender("MALE");
        patient.setCreatedBy("doctor");
        patient.setStatus("ACTIVE");
        patient.setRiskLevel("MEDIUM");
        return patient;
    }

    private CareDocument draftReport() {
        CareDocument document = new CareDocument();
        document.setId("cdoc_1");
        document.setOwner("doctor");
        document.setKind(CareService.KIND_REPORT);
        document.setPatientId("pat_1");
        document.setStatus(CareService.STATUS_DRAFT);
        document.setRevision(1);
        document.setSourceExamId("pie_1");
        document.setSourceExamRevision(0);
        document.setParentDocumentId(null);
        document.setSections(Json.write(Map.of("profile",
                Map.of("text", "综合健康画像正文，足够长以通过校验。", "metricRefs", List.of("FBG")))));
        document.setAggregate(Json.write(Map.of("complete", true)));
        document.setCreatedAt(OffsetDateTime.now());
        document.setUpdatedAt(OffsetDateTime.now());
        return document;
    }

    private CareDocument existingReport() {
        CareDocument document = draftReport();
        document.setId("cdoc_existing");
        document.setIdempotencyKey("key-1");
        document.setInputFingerprint(com.kangheng.healthos.common.Ids.sha256Hex(
                Json.write(Map.of("examId", "pie_1", "patientId", "pat_1", "examRevision", "0"))));
        return document;
    }

    private CareDocument planDocument(String status) {
        CareDocument document = new CareDocument();
        document.setId("cdoc_plan");
        document.setOwner("doctor");
        document.setKind(CareService.KIND_PLAN);
        document.setPatientId("pat_1");
        document.setStatus(status);
        document.setRevision(2);
        document.setSourceReportId("cdoc_1");
        document.setSourceReportRevision(1);
        document.setSections(Json.write(Map.of(
                "framework", Map.of("text", "四周计划总体框架正文。", "metricRefs", List.of()),
                "week_1", Map.of("text", "第 1 周执行计划正文。", "metricRefs", List.of()))));
        document.setAggregate(Json.write(Map.of("complete", true)));
        document.setCreatedAt(OffsetDateTime.now());
        document.setUpdatedAt(OffsetDateTime.now());
        return document;
    }

    private CarePatientState state() {
        CarePatientState state = new CarePatientState();
        state.setPatientId("pat_1");
        state.setOwner("doctor");
        state.setVersion(1);
        state.setUpdatedAt(OffsetDateTime.now());
        return state;
    }
}
