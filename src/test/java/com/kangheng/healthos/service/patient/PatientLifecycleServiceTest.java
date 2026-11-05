package com.kangheng.healthos.service.patient;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.contract.patient.PatientContract;
import com.kangheng.healthos.mapper.patient.PatientMapper;
import com.kangheng.healthos.mapper.patient.PatientRevisionMapper;
import com.kangheng.healthos.model.patient.Patient;
import com.kangheng.healthos.model.patient.PatientRevision;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PatientLifecycleServiceTest {

    private PatientMapper patientMapper;
    private PatientRevisionMapper revisionMapper;
    private PatientLifecycleService service;

    /**
     * MyBatis-Plus 的 lambda 条件在渲染 SQL 时才解析列名，单测里没有 MyBatis 上下文
     * 会直接抛 "can not find lambda cache"。手工初始化一次实体元数据，
     * 断言才能看到真实的 WHERE 子句——否则"查询带了 owner"这句话只是注释，不是事实。
     */
    @BeforeAll
    static void initMybatisMeta() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Patient.class);
    }

    @BeforeEach
    void setUp() {
        patientMapper = mock(PatientMapper.class);
        revisionMapper = mock(PatientRevisionMapper.class);
        service = new PatientLifecycleService(patientMapper, revisionMapper);
    }

    private static Patient patient(String status, int revision) {
        Patient patient = new Patient();
        patient.setId("pat_1");
        patient.setName("张三");
        patient.setGender("MALE");
        patient.setStatus(status);
        patient.setRevision(revision);
        patient.setCreatedBy("admin");
        patient.setCreatedAt(OffsetDateTime.parse("2026-11-01T10:00:00+08:00"));
        patient.setUpdatedAt(OffsetDateTime.parse("2026-11-01T10:00:00+08:00"));
        return patient;
    }

    @Test
    @DisplayName("草稿建档：状态 DRAFT、版本 0，并留下一条快照")
    void createsDraftWithSnapshot() {
        when(patientMapper.selectOne(any())).thenReturn(null);

        PatientContract.PatientView view = service.create(new PatientContract.CreatePatientRequest(
                " 张三 ", "MALE", LocalDate.parse("1965-04-12"), "13800000000", null, true), "admin");

        assertThat(view.status()).isEqualTo("DRAFT");
        assertThat(view.revision()).isZero();
        assertThat(view.name()).isEqualTo("张三");
        verify(patientMapper).insert(any(Patient.class));
        ArgumentCaptor<PatientRevision> captor = ArgumentCaptor.forClass(PatientRevision.class);
        verify(revisionMapper).insert(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo("创建档案");
        assertThat(captor.getValue().getSha256()).hasSize(64);
        // 快照也要带归属人，否则读快照时无法过滤
        assertThat(captor.getValue().getOwner()).isEqualTo("admin");
    }

    @Test
    @DisplayName("外部业务号重复时拒绝建档（冲突 409 语义）")
    void rejectsDuplicateExternalRef() {
        when(patientMapper.selectOne(any())).thenReturn(patient("ACTIVE", 0));

        assertThatThrownBy(() -> service.create(new PatientContract.CreatePatientRequest(
                "李四", "FEMALE", null, null, "E-100", false), "admin"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo("PATIENT_EXTERNAL_REF_DUPLICATED");
        verify(patientMapper, never()).insert(any(Patient.class));
    }

    @Test
    @DisplayName("版本号不一致：拒绝更新，且不写快照")
    void rejectsStaleRevision() {
        when(patientMapper.selectOne(any())).thenReturn(patient("ACTIVE", 3));
        when(patientMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.update("pat_1",
                new PatientContract.UpdatePatientRequest(1, "王五", null, null, null, null, null), "admin"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(PatientLifecycleService.ERROR_CONFLICT);

        verify(patientMapper, never()).update(any(Patient.class), any());
        verify(revisionMapper, never()).insert(any(PatientRevision.class));
    }

    @Test
    @DisplayName("版本号一致：更新成功并写入新版本快照")
    void updatesWhenRevisionMatches() {
        Patient current = patient("ACTIVE", 3);
        Patient updated = patient("ACTIVE", 4);
        updated.setName("王五");
        when(patientMapper.selectOne(any())).thenReturn(current, updated);
        when(patientMapper.update(any(), any())).thenReturn(1);

        PatientContract.PatientView view = service.update("pat_1",
                new PatientContract.UpdatePatientRequest(3, "王五", null, null, null, null, "改名"),
                "admin");

        assertThat(view.name()).isEqualTo("王五");
        ArgumentCaptor<PatientRevision> captor = ArgumentCaptor.forClass(PatientRevision.class);
        verify(revisionMapper).insert(captor.capture());
        assertThat(captor.getValue().getRevision()).isEqualTo(4);
        assertThat(captor.getValue().getReason()).isEqualTo("改名");
    }

    @Test
    @DisplayName("已归档的档案不允许直接编辑")
    void rejectsEditOnArchived() {
        when(patientMapper.selectOne(any())).thenReturn(patient("ARCHIVED", 1));

        assertThatThrownBy(() -> service.update("pat_1",
                new PatientContract.UpdatePatientRequest(1, "王五", null, null, null, null, null), "admin"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("归档");
    }

    @Test
    @DisplayName("非法状态流转被拒绝：ACTIVE 不能再确认")
    void rejectsIllegalTransition() {
        when(patientMapper.selectOne(any())).thenReturn(patient("ACTIVE", 0));

        assertThatThrownBy(() -> service.confirm("pat_1",
                new PatientContract.TransitionRequest(0, null), "admin"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(PatientLifecycleService.ERROR_TRANSITION);
    }

    @Test
    @DisplayName("DELETED 是终态：不能恢复")
    void deletedIsTerminal() {
        when(patientMapper.selectOne(any())).thenReturn(patient("DELETED", 2));

        assertThatThrownBy(() -> service.restore("pat_1",
                new PatientContract.TransitionRequest(2, null), "admin"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(PatientLifecycleService.ERROR_TRANSITION);
    }

    @Test
    @DisplayName("草稿确认后变成 ACTIVE 且版本 +1")
    void confirmsDraft() {
        Patient draft = patient("DRAFT", 0);
        Patient active = patient("ACTIVE", 1);
        when(patientMapper.selectOne(any())).thenReturn(draft, active);
        when(patientMapper.update(any(), any())).thenReturn(1);

        PatientContract.PatientView view = service.confirm("pat_1",
                new PatientContract.TransitionRequest(0, null), "admin");

        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.revision()).isEqualTo(1);
    }

    // ------------------------------------------------------------ owner 隔离

    @Test
    @DisplayName("取档案时 WHERE 必须带上归属人")
    void ownerIsPartOfTheQuery() {
        when(patientMapper.selectOne(any())).thenReturn(patient("ACTIVE", 0));

        service.require("pat_1", "bob");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Patient>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(patientMapper).selectOne(captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("created_by");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("bob");
    }

    @Test
    @DisplayName("别人的档案：404 而不是 403（不泄露存在性）")
    void otherOwnerGetsNotFound() {
        // 查询按 owner 过滤后自然查不到
        when(patientMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.require("pat_1", "mallory"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getStatus().value())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("更新时 WHERE 同时带归属人与版本号")
    void updateQueryCarriesOwnerAndRevision() {
        when(patientMapper.selectOne(any())).thenReturn(patient("ACTIVE", 3));
        when(patientMapper.update(any(), any())).thenReturn(1);

        service.update("pat_1",
                new PatientContract.UpdatePatientRequest(3, "王五", null, null, null, null, null), "bob");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Patient>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(patientMapper).update(any(Patient.class), captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("created_by", "revision");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("bob", 3);
    }

    @Test
    @DisplayName("后台取档案不校验归属：worker 按 ID 处理系统级任务")
    void internalLookupSkipsOwnerCheck() {
        when(patientMapper.selectById("pat_1")).thenReturn(patient("ACTIVE", 0));

        assertThat(service.requireInternal("pat_1").getId()).isEqualTo("pat_1");
        verify(patientMapper).selectById("pat_1");
    }
}
