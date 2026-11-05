package com.kangheng.healthos.service.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.contract.patient.PatientMemoryContract;
import com.kangheng.healthos.mapper.patient.PatientMapper;
import com.kangheng.healthos.mapper.patient.PatientMemoryStateMapper;
import com.kangheng.healthos.mapper.patient.PatientMemoryVersionMapper;
import com.kangheng.healthos.model.care.CareCheckin;
import com.kangheng.healthos.model.care.CareDocument;
import com.kangheng.healthos.model.care.CarePatientState;
import com.kangheng.healthos.model.care.CareRevision;
import com.kangheng.healthos.model.care.CareSignature;
import com.kangheng.healthos.model.patient.Patient;
import com.kangheng.healthos.model.patient.PatientFollowup;
import com.kangheng.healthos.model.patient.PatientMemoryJob;
import com.kangheng.healthos.model.patient.PatientMemoryState;
import com.kangheng.healthos.model.patient.PatientMemoryVersion;
import com.kangheng.healthos.model.patient.PatientMetric;
import com.kangheng.healthos.model.patient.PatientRevision;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 记忆版本链的边界。
 *
 * <p>最要紧的一条是"历史不可改"：刷新只 insert 新版本、只 update 指针，
 * 任何对历史行的 update 都说明实现走歪了。第二条是 contentHash 必须真的等于
 * Markdown 的 SHA-256——导出校验靠的就是这个等式。
 */
class PatientMemoryServiceTest {

    private PatientMapper patientMapper;
    private PatientMemoryVersionMapper versionMapper;
    private PatientMemoryStateMapper stateMapper;
    private MemorySourceService sourceService;
    private PatientMemoryService service;

    @BeforeAll
    static void initMeta() {
        for (Class<?> entity : List.of(Patient.class, PatientMemoryVersion.class,
                PatientMemoryState.class, PatientFollowup.class, PatientMemoryJob.class,
                PatientRevision.class, PatientMetric.class, CarePatientState.class,
                CareRevision.class, CareSignature.class, CareCheckin.class, CareDocument.class)) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
    }

    @BeforeEach
    void setUp() {
        patientMapper = mock(PatientMapper.class);
        versionMapper = mock(PatientMemoryVersionMapper.class);
        stateMapper = mock(PatientMemoryStateMapper.class);
        sourceService = mock(MemorySourceService.class);
        service = new PatientMemoryService(patientMapper, versionMapper, stateMapper,
                sourceService, new MemoryMarkdownRenderer());

        Patient patient = new Patient();
        patient.setId("pat_1");
        patient.setCreatedBy("alice");
        when(patientMapper.selectById("pat_1")).thenReturn(patient);
        when(patientMapper.lockForUpdate("pat_1")).thenReturn(patient);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", "memory-1");
        List<Map<String, Object>> sources = List.of(Map.of("kind", "patient_revision",
                "id", "prev_1", "contentHash", "abc"));
        when(sourceService.capture(anyString(), anyString(), any()))
                .thenReturn(new MemorySourceService.Capture(data, sources));
    }

    @Test
    @DisplayName("初始化发布 v1，contentHash 真的等于 Markdown 的 SHA-256")
    void initializePublishesFirstVersion() {
        PatientMemoryContract.VersionView view = service.initialize("alice", "pat_1");

        assertThat(view.version()).isEqualTo(1);
        assertThat(view.generationMode()).isEqualTo("programmatic_facts");
        assertThat(view.contentHash()).isEqualTo(Ids.sha256Hex(view.markdown()));
        assertThat(view.sourceCount()).isEqualTo(1);

        ArgumentCaptor<PatientMemoryVersion> captor =
                ArgumentCaptor.forClass(PatientMemoryVersion.class);
        verify(versionMapper).insert(captor.capture());
        assertThat(captor.getValue().getContentHash())
                .isEqualTo(Ids.sha256Hex(captor.getValue().getMarkdown()));
        verify(stateMapper).insert(any(PatientMemoryState.class));
    }

    @Test
    @DisplayName("已初始化时 initialize 幂等返回当前版本，不产生第二个版本")
    void initializeIsIdempotent() {
        PatientMemoryState state = new PatientMemoryState();
        state.setPatientId("pat_1");
        state.setOwner("alice");
        state.setCurrentMemoryId("pmem_existing");
        state.setVersion(7);
        when(stateMapper.selectById("pat_1")).thenReturn(state);
        when(versionMapper.selectById("pmem_existing")).thenReturn(row("pmem_existing", 7));

        PatientMemoryContract.VersionView view = service.initialize("alice", "pat_1");

        assertThat(view.version()).isEqualTo(7);
        verify(versionMapper, never()).insert(any(PatientMemoryVersion.class));
    }

    @Test
    @DisplayName("刷新只追加版本、只移动指针，绝不改写历史行")
    void publishNeverUpdatesHistory() {
        service.publish("alice", "pat_1");

        PatientMemoryState state = new PatientMemoryState();
        state.setPatientId("pat_1");
        state.setOwner("alice");
        state.setCurrentMemoryId("pmem_v1");
        state.setVersion(1);
        when(stateMapper.selectById("pat_1")).thenReturn(state);
        service.publish("alice", "pat_1");

        ArgumentCaptor<PatientMemoryVersion> captor =
                ArgumentCaptor.forClass(PatientMemoryVersion.class);
        verify(versionMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(PatientMemoryVersion::getVersion)
                .containsExactly(1, 2);
        verify(versionMapper, never()).updateById(any(PatientMemoryVersion.class));
        verify(stateMapper).updateById(any(PatientMemoryState.class));
    }

    @Test
    @DisplayName("sourceHash 剔除采样时间：来源没变时两版摘要相同")
    void sourceHashIgnoresSampledAt() {
        service.publish("alice", "pat_1");
        PatientMemoryState state = new PatientMemoryState();
        state.setPatientId("pat_1");
        state.setOwner("alice");
        state.setCurrentMemoryId("pmem_v1");
        state.setVersion(1);
        when(stateMapper.selectById("pat_1")).thenReturn(state);
        service.publish("alice", "pat_1");

        ArgumentCaptor<PatientMemoryVersion> captor =
                ArgumentCaptor.forClass(PatientMemoryVersion.class);
        verify(versionMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues().get(0).getSourceHash())
                .isEqualTo(captor.getAllValues().get(1).getSourceHash());
    }

    @Test
    @DisplayName("越权访问患者与跨患者取版本都是 404")
    void ownerIsolation() {
        assertThatThrownBy(() -> service.current("bob", "pat_1"))
                .isInstanceOf(BizException.class)
                .extracting("status").isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);

        PatientMemoryVersion otherPatient = row("pmem_1", 1);
        otherPatient.setOwner("alice");
        otherPatient.setPatientId("pat_2");
        when(versionMapper.selectById("pmem_1")).thenReturn(otherPatient);
        assertThatThrownBy(() -> service.version("alice", "pat_1", "pmem_1"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("客户端拿着过期版本提交时 409，不静默覆盖")
    void staleExpectedVersionIsRejected() {
        PatientMemoryState state = new PatientMemoryState();
        state.setCurrentMemoryId("pmem_v2");
        state.setVersion(2);
        when(stateMapper.selectById("pat_1")).thenReturn(state);

        assertThatThrownBy(() -> service.assertExpected("alice", "pat_1", "pmem_v1"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode())
                        .isEqualTo(PatientMemoryService.ERROR_VERSION_CONFLICT));
        service.assertExpected("alice", "pat_1", "pmem_v2");
    }

    @Test
    @DisplayName("未初始化时 current 明确 404，不返回空壳")
    void currentRequiresInitialization() {
        assertThatThrownBy(() -> service.current("alice", "pat_1"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode())
                        .isEqualTo("MEMORY_NOT_INITIALIZED"));
    }

    @Test
    @DisplayName("导出带出版本里记录的哈希，且内容与哈希自洽")
    void exportCarriesRecordedHash() {
        PatientMemoryVersion row = row("pmem_1", 1);
        row.setMarkdown("# 患者长效记忆 v1\n");
        row.setContentHash(Ids.sha256Hex(row.getMarkdown()));
        row.setSources("[]");
        row.setData("{}");
        when(versionMapper.selectById("pmem_1")).thenReturn(row);

        PatientMemoryService.ExportFile file = service.export("alice", "pat_1", "pmem_1");

        assertThat(file.filename()).isEqualTo("patient-memory-v1.md");
        assertThat(file.contentHash()).isEqualTo(Ids.sha256Hex(row.getMarkdown()));
        assertThat(new String(file.content(), StandardCharsets.UTF_8)).isEqualTo(row.getMarkdown());
        assertThat(Ids.sha256Hex(new String(file.content(), StandardCharsets.UTF_8)))
                .isEqualTo(file.contentHash());
    }

    private PatientMemoryVersion row(String id, int version) {
        PatientMemoryVersion row = new PatientMemoryVersion();
        row.setId(id);
        row.setPatientId("pat_1");
        row.setOwner("alice");
        row.setVersion(version);
        row.setContentHash("hash-" + version);
        row.setSourceHash("src-" + version);
        row.setMarkdown("# v" + version + "\n");
        row.setSources("[]");
        row.setData("{}");
        return row;
    }
}
