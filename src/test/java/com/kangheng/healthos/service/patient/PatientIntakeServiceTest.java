package com.kangheng.healthos.service.patient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import com.kangheng.healthos.service.classics.DocumentNormalizer;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.service.outbox.OutboxService;
import com.kangheng.healthos.contract.patient.PatientContract;
import com.kangheng.healthos.provider.patient.RuleBasedPatientRecordExtractor;
import com.kangheng.healthos.mapper.patient.PatientIntakeExamMapper;
import com.kangheng.healthos.mapper.patient.PatientIntakePartMapper;
import com.kangheng.healthos.model.patient.PatientIntakeExam;
import com.kangheng.healthos.model.patient.PatientIntakePart;
import com.kangheng.healthos.spi.storage.ObjectStorage;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
 * 资料录入的流程约束。
 *
 * <p>这一组用例守的是<b>流程关卡</b>，不是抽取细节（抽取细节在
 * {@code RuleBasedPatientRecordExtractorTest} 里）。
 *
 * <p>核心主张只有一条：<b>AI 只负责抄，签字的是人</b>。
 * 所以任何一条绕过两道人工核查、或在有分片抽取失败时生成档案的路径，
 * 都必须被测试挡住——这类缺陷在演示里看不出来，在真实数据上会变成残缺病历。
 */
class PatientIntakeServiceTest {

    private PatientIntakeExamMapper examMapper;
    private PatientIntakePartMapper partMapper;
    private PatientLifecycleService lifecycle;
    private PatientService patientService;
    private ObjectStorage storage;
    private OutboxService outboxService;
    private HealthOsProperties properties;
    private PatientIntakeService service;

    @BeforeAll
    static void initMybatisMeta() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PatientIntakeExam.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PatientIntakePart.class);
    }

    @BeforeEach
    void setUp() {
        examMapper = mock(PatientIntakeExamMapper.class);
        partMapper = mock(PatientIntakePartMapper.class);
        lifecycle = mock(PatientLifecycleService.class);
        patientService = mock(PatientService.class);
        storage = mock(ObjectStorage.class);
        outboxService = mock(OutboxService.class);
        properties = new HealthOsProperties();
        service = new PatientIntakeService(examMapper, partMapper, lifecycle, patientService,
                new RuleBasedPatientRecordExtractor(), mock(DocumentNormalizer.class), storage,
                outboxService, properties);
    }

    private static PatientIntakeExam exam(String status, int revision) {
        PatientIntakeExam exam = new PatientIntakeExam();
        exam.setId("pie_1");
        exam.setOwner("doctor");
        exam.setSourceFilename("report.txt");
        exam.setSourceSha256("abc");
        exam.setStatus(status);
        exam.setBasicReviewStatus("PENDING");
        exam.setClinicalReviewStatus("PENDING");
        exam.setRevision(revision);
        exam.setCreatedAt(OffsetDateTime.parse("2026-11-01T10:00:00+08:00"));
        exam.setUpdatedAt(OffsetDateTime.parse("2026-11-01T10:00:00+08:00"));
        return exam;
    }

    private void mockExam(PatientIntakeExam exam) {
        when(examMapper.selectOne(any())).thenReturn(exam);
        when(partMapper.selectList(any())).thenReturn(List.of());
    }

    // ------------------------------------------------------------ 上传

    @Test
    @DisplayName("上传：落录入单 + 写一条抽取事件，不同步跑 OCR")
    void submitPersistsAndEnqueues() {
        when(examMapper.selectOne(any())).thenReturn(null);
        when(storage.putAt(anyString(), any())).thenReturn(
                new ObjectStorage.StoredObject("patient-intake/doctor/pie_1.txt", "sha", 12));

        PatientContract.IntakeSubmitResponse response =
                service.submit("report.txt", "text/plain", "姓名：张三".getBytes(), "doctor");

        assertThat(response.examId()).isNotBlank();
        assertThat(response.reused()).isFalse();
        ArgumentCaptor<PatientIntakeExam> captor = ArgumentCaptor.forClass(PatientIntakeExam.class);
        verify(examMapper).insert(captor.capture());
        assertThat(captor.getValue().getOwner()).isEqualTo("doctor");
        assertThat(captor.getValue().getStatus()).isEqualTo("UPLOADED");
        verify(outboxService).append(eq("patient_intake"), anyString(),
                eq(PatientIntakeService.EVENT_EXTRACT), any());
    }

    @Test
    @DisplayName("同一份资料重复上传：复用原录入单，不重跑 OCR")
    void submitIsIdempotentByContent() {
        when(examMapper.selectOne(any())).thenReturn(exam("EXTRACTED", 1));

        PatientContract.IntakeSubmitResponse response =
                service.submit("report.txt", "text/plain", "姓名：张三".getBytes(), "doctor");

        assertThat(response.reused()).isTrue();
        assertThat(response.examId()).isEqualTo("pie_1");
        verify(examMapper, never()).insert(any(PatientIntakeExam.class));
        verify(outboxService, never()).append(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("上传空文件直接拒绝")
    void rejectsEmptyUpload() {
        assertThatThrownBy(() -> service.submit("a.txt", "text/plain", new byte[0], "doctor"))
                .isInstanceOf(BizException.class);
        verify(examMapper, never()).insert(any(PatientIntakeExam.class));
    }

    @Test
    @DisplayName("上传超限文件直接拒绝，不落库")
    void rejectsOversizedUpload() {
        properties.getPatient().setMaxObjectBytes(16);

        assertThatThrownBy(() -> service.submit("a.txt", "text/plain", new byte[64], "doctor"))
                .isInstanceOf(BizException.class);
        verify(examMapper, never()).insert(any(PatientIntakeExam.class));
    }

    @Test
    @DisplayName("上传：失败的录入单允许重试——上一轮 OCR 挂了不该让这份资料永远卡住")
    void failedExamCanBeRetried() {
        PatientIntakeExam failed = exam("FAILED", 0);
        when(examMapper.selectOne(any())).thenReturn(failed);

        PatientContract.IntakeSubmitResponse response =
                service.submit("report.txt", "text/plain", "姓名：张三".getBytes(), "doctor");

        assertThat(response.examId()).isEqualTo("pie_1");
        assertThat(response.status()).isEqualTo("UPLOADED");
        // 重新排队，并且把上一轮的分片清掉，避免两次抽取的结果混在一起
        verify(partMapper).delete(any());
        verify(outboxService).append(eq("patient_intake"), eq("pie_1"),
                eq(PatientIntakeService.EVENT_EXTRACT), any());
    }

    @Test
    @DisplayName("上传：已抽取完成的录入单不重跑 OCR")
    void extractedExamIsNotReprocessed() {
        when(examMapper.selectOne(any())).thenReturn(exam("EXTRACTED", 1));

        service.submit("report.txt", "text/plain", "姓名：张三".getBytes(), "doctor");

        verify(partMapper, never()).delete(any());
        verify(outboxService, never()).append(anyString(), anyString(), anyString(), any());
    }

    // ------------------------------------------------------------ 核查

    @Test
    @DisplayName("核查：驳回必须写明原因，只标 REJECTED 等于没交代")
    void rejectionRequiresNote() {
        mockExam(exam("EXTRACTED", 0));

        assertThatThrownBy(() -> service.review("pie_1",
                new PatientContract.IntakeReviewRequest("REJECTED", null, null, null), "doctor"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("原因");
    }

    @Test
    @DisplayName("核查：结论只能是 CONFIRMED 或 REJECTED")
    void rejectsUnknownDecision() {
        mockExam(exam("EXTRACTED", 0));

        assertThatThrownBy(() -> service.review("pie_1",
                new PatientContract.IntakeReviewRequest("MAYBE", "随便看看", null, null), "doctor"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("核查：状态不是 EXTRACTED 时不能核查")
    void reviewRequiresExtractedState() {
        mockExam(exam("UPLOADED", 0));

        assertThatThrownBy(() -> service.review("pie_1",
                new PatientContract.IntakeReviewRequest("CONFIRMED", "ok", null, null), "doctor"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能核查");
    }

    @Test
    @DisplayName("核查：别人家的录入单查不到")
    void reviewRejectsOtherOwner() {
        when(examMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.review("pie_1",
                new PatientContract.IntakeReviewRequest("CONFIRMED", "ok", null, null), "doctor"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("核查：两道关卡可以分别表态，互不覆盖")
    void reviewsAreIndependent() {
        PatientIntakeExam exam = exam("EXTRACTED", 0);
        mockExam(exam);

        service.review("pie_1",
                new PatientContract.IntakeReviewRequest("CONFIRMED", "基础信息无误", null, null), "doctor");

        assertThat(exam.getBasicReviewStatus()).isEqualTo("CONFIRMED");
        assertThat(exam.getClinicalReviewStatus()).isEqualTo("PENDING");
    }

    // ------------------------------------------------------------ 确认建档

    @Test
    @DisplayName("确认：两道核查没都通过就不许建档")
    void confirmRequiresBothReviews() {
        PatientIntakeExam exam = exam("EXTRACTED", 0);
        exam.setBasicReviewStatus("CONFIRMED");
        exam.setClinicalReviewStatus("PENDING");
        exam.setAggregate("{}");
        mockExam(exam);

        assertThatThrownBy(() -> service.confirm("pie_1",
                new PatientContract.IntakeConfirmRequest(0, null), "doctor"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("临床内容核查");
        verify(lifecycle, never()).create(any(), anyString());
    }

    @Test
    @DisplayName("确认：版本冲突拒绝，防止覆盖别人刚改过的核查结果")
    void confirmRejectsStaleRevision() {
        PatientIntakeExam exam = exam("EXTRACTED", 3);
        exam.setBasicReviewStatus("CONFIRMED");
        exam.setClinicalReviewStatus("CONFIRMED");
        mockExam(exam);

        assertThatThrownBy(() -> service.confirm("pie_1",
                new PatientContract.IntakeConfirmRequest(1, null), "doctor"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("版本冲突");
        verify(lifecycle, never()).create(any(), anyString());
    }

    @Test
    @DisplayName("确认：抽不到姓名也不许建档——宁可让人补，也不要半残档案")
    void confirmRequiresName() {
        PatientIntakeExam exam = exam("EXTRACTED", 0);
        exam.setBasicReviewStatus("CONFIRMED");
        exam.setClinicalReviewStatus("CONFIRMED");
        exam.setAggregate("{\"fields\":{\"gender\":\"男\"},\"metrics\":[]}");
        mockExam(exam);

        assertThatThrownBy(() -> service.confirm("pie_1",
                new PatientContract.IntakeConfirmRequest(0, null), "doctor"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("姓名");
        verify(lifecycle, never()).create(any(), anyString());
    }

    @Test
    @DisplayName("确认：人工补录的姓名可以建档，并标注来源为 manual")
    void confirmAcceptsManualFields() {
        PatientIntakeExam exam = exam("EXTRACTED", 0);
        exam.setBasicReviewStatus("CONFIRMED");
        exam.setClinicalReviewStatus("CONFIRMED");
        exam.setAggregate("{\"fields\":{\"gender\":\"男\"},\"fieldSources\":{\"gender\":\"general\"},"
                + "\"metrics\":[]}");
        mockExam(exam);
        when(lifecycle.create(any(), eq("doctor"))).thenReturn(
                new PatientContract.PatientView("pat_1", "王五", "MALE", null, null,
                        "LOW", "ACTIVE", 0, null, "doctor", OffsetDateTime.now(), null, null));

        PatientContract.IntakeConfirmResult result = service.confirm("pie_1",
                new PatientContract.IntakeConfirmRequest(0, null, "王五", "男", null, null), "doctor");

        assertThat(result.patientId()).isEqualTo("pat_1");
        ArgumentCaptor<PatientContract.CreatePatientRequest> captor =
                ArgumentCaptor.forClass(PatientContract.CreatePatientRequest.class);
        verify(lifecycle).create(captor.capture(), eq("doctor"));
        assertThat(captor.getValue().name()).isEqualTo("王五");
        // 补录的字段来源标成 manual，抽取的仍保留分片键
        assertThat(exam.getAggregate()).contains("\"name\":\"王五\"");
        assertThat(exam.getAggregate()).contains("manual");
        assertThat(exam.getAggregate()).contains("general");
    }

    @Test
    @DisplayName("确认：人工补录优先于抽取结果")
    void manualFieldsOverrideExtracted() {
        PatientIntakeExam exam = exam("EXTRACTED", 0);
        exam.setBasicReviewStatus("CONFIRMED");
        exam.setClinicalReviewStatus("CONFIRMED");
        exam.setAggregate("{\"fields\":{\"name\":\"张三\",\"gender\":\"男\"},\"metrics\":[]}");
        mockExam(exam);
        when(lifecycle.create(any(), eq("doctor"))).thenReturn(
                new PatientContract.PatientView("pat_1", "张三", "MALE", null, null,
                        "LOW", "ACTIVE", 0, null, "doctor", OffsetDateTime.now(), null, null));

        service.confirm("pie_1",
                new PatientContract.IntakeConfirmRequest(0, null, "李四", null, null, null), "doctor");

        ArgumentCaptor<PatientContract.CreatePatientRequest> captor =
                ArgumentCaptor.forClass(PatientContract.CreatePatientRequest.class);
        verify(lifecycle).create(captor.capture(), eq("doctor"));
        assertThat(captor.getValue().name()).isEqualTo("李四");
    }

    @Test
    @DisplayName("确认：抽不到性别也不许建档")
    void confirmRequiresGender() {
        PatientIntakeExam exam = exam("EXTRACTED", 0);
        exam.setBasicReviewStatus("CONFIRMED");
        exam.setClinicalReviewStatus("CONFIRMED");
        exam.setAggregate("{\"fields\":{\"name\":\"张三\"},\"metrics\":[]}");
        mockExam(exam);

        assertThatThrownBy(() -> service.confirm("pie_1",
                new PatientContract.IntakeConfirmRequest(0, null), "doctor"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("性别");
        verify(lifecycle, never()).create(any(), anyString());
    }

    @Test
    @DisplayName("确认：状态不是 EXTRACTED 时不能确认")
    void confirmRequiresExtractedState() {
        mockExam(exam("UPLOADED", 0));

        assertThatThrownBy(() -> service.confirm("pie_1",
                new PatientContract.IntakeConfirmRequest(0, null), "doctor"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能确认");
    }

    @Test
    @DisplayName("确认：生成档案 + 写入指标，并把档案 ID 回写到录入单")
    void confirmCreatesPatientAndMetrics() {
        PatientIntakeExam exam = exam("EXTRACTED", 0);
        exam.setBasicReviewStatus("CONFIRMED");
        exam.setClinicalReviewStatus("CONFIRMED");
        exam.setAggregate("""
                {"fields":{"name":"张三","gender":"男"},
                 "metrics":[{"code":"SBP","name":"收缩压","value":130,"unit":"mmHg",
                             "referenceLow":90,"referenceHigh":139}]}
                """);
        mockExam(exam);
        when(lifecycle.create(any(), eq("doctor"))).thenReturn(
                new PatientContract.PatientView("pat_1", "张三", "MALE", null, null,
                        "LOW", "ACTIVE", 0, null, "doctor",
                        OffsetDateTime.now(), null, null));

        PatientContract.IntakeConfirmResult result = service.confirm("pie_1",
                new PatientContract.IntakeConfirmRequest(0, null), "doctor");

        assertThat(result.patientId()).isEqualTo("pat_1");
        assertThat(result.metricCount()).isEqualTo(1);
        assertThat(exam.getPatientId()).isEqualTo("pat_1");
        assertThat(exam.getStatus()).isEqualTo("CONFIRMED");
        verify(patientService).upsertMetrics(eq("pat_1"), any(), eq("doctor"));
    }

    // ------------------------------------------------------------ 分片切分

    @Test
    @DisplayName("分片：按标题切开，标题以下的内容归该分片")
    void splitsByHeader() {
        Map<String, String> parts = PatientIntakeService.splitParts("""
                主诉
                头痛三天

                体格检查
                血压 130/80
                """);

        assertThat(parts).containsKeys("chief_complaint", "physical_exam");
        assertThat(parts.get("chief_complaint")).contains("头痛三天");
        assertThat(parts.get("physical_exam")).contains("血压 130/80");
    }

    @Test
    @DisplayName("分片：markdown 标题、冒号、中文序号、加粗都能认")
    void recognisesHeaderVariants() {
        Map<String, String> parts = PatientIntakeService.splitParts("""
                ### 主诉：
                头痛

                **体格检查**
                血压正常

                一、既往史
                高血压病史
                """);

        assertThat(parts).containsKeys("chief_complaint", "physical_exam", "past_history");
    }

    @Test
    @DisplayName("分片：没有标题的内容归 general，不丢信息")
    void fallsBackToGeneral() {
        Map<String, String> parts = PatientIntakeService.splitParts("姓名：张三 性别：男");

        assertThat(parts).containsKey("general");
        assertThat(parts.get("general")).contains("张三");
    }

    @Test
    @DisplayName("分片：\"检查检验\" 是独立分片，不会掉进 general")
    void recognisesLabExamHeader() {
        Map<String, String> parts = PatientIntakeService.splitParts("""
                检查检验
                空腹血糖 5.6
                """);

        assertThat(parts).containsKey("lab_exam");
    }

    @Test
    @DisplayName("分片：正文里出现\"检查\"两个字不等于一行标题")
    void doesNotTreatBodyTextAsHeader() {
        Map<String, String> parts = PatientIntakeService.splitParts(
                "患者自述今日检查完毕，医生建议复查甲状腺功能与腹部超声");

        assertThat(parts).containsOnlyKeys("general");
    }
}
