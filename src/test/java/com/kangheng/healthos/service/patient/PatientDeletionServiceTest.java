package com.kangheng.healthos.service.patient;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.service.outbox.OutboxService;
import com.kangheng.healthos.contract.patient.PatientContract;
import com.kangheng.healthos.mapper.patient.PatientBatchRowMapper;
import com.kangheng.healthos.mapper.patient.PatientDeletionResourceMapper;
import com.kangheng.healthos.mapper.patient.PatientDeletionTaskMapper;
import com.kangheng.healthos.mapper.patient.PatientMapper;
import com.kangheng.healthos.mapper.patient.PatientMetricMapper;
import com.kangheng.healthos.mapper.patient.PatientObjectMapper;
import com.kangheng.healthos.mapper.patient.PatientRevisionMapper;
import com.kangheng.healthos.model.patient.Patient;
import com.kangheng.healthos.model.patient.PatientBatchRow;
import com.kangheng.healthos.model.patient.PatientDeletionResource;
import com.kangheng.healthos.model.patient.PatientDeletionTask;
import com.kangheng.healthos.model.patient.PatientMetric;
import com.kangheng.healthos.model.patient.PatientObjectEntity;
import com.kangheng.healthos.model.patient.PatientRevision;
import com.kangheng.healthos.spi.storage.ObjectStorage;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PatientDeletionServiceTest {

    private PatientDeletionTaskMapper taskMapper;
    private PatientDeletionResourceMapper resourceMapper;
    private PatientMetricMapper metricMapper;
    private PatientRevisionMapper revisionMapper;
    private PatientObjectMapper objectMapper;
    private PatientBatchRowMapper batchRowMapper;
    private PatientLifecycleService lifecycle;
    private ObjectStorage storage;
    private PatientDeletionService service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(PatientDeletionTaskMapper.class);
        resourceMapper = mock(PatientDeletionResourceMapper.class);
        metricMapper = mock(PatientMetricMapper.class);
        revisionMapper = mock(PatientRevisionMapper.class);
        objectMapper = mock(PatientObjectMapper.class);
        batchRowMapper = mock(PatientBatchRowMapper.class);
        lifecycle = mock(PatientLifecycleService.class);
        storage = mock(ObjectStorage.class);
        service = new PatientDeletionService(taskMapper, resourceMapper, mock(PatientMapper.class),
                metricMapper, revisionMapper, objectMapper, batchRowMapper, lifecycle,
                mock(OutboxService.class), storage);
    }

    private static Patient patient() {
        Patient patient = new Patient();
        patient.setId("pat_1");
        patient.setRevision(2);
        patient.setStatus("ACTIVE");
        patient.setCreatedBy("admin");
        return patient;
    }

    @Test
    @DisplayName("预览会算清每一类资源，并给出一次性令牌")
    void previewCollectsEveryResourceKind() {
        when(lifecycle.require("pat_1", "admin")).thenReturn(patient());
        when(metricMapper.selectList(any())).thenReturn(List.of(metric("m1"), metric("m2")));
        when(revisionMapper.selectList(any())).thenReturn(List.of(revision("r1")));
        when(objectMapper.selectList(any())).thenReturn(List.of(object("o1", "patients/pat_1/o1.pdf")));
        when(batchRowMapper.selectList(any())).thenReturn(List.of(batchRow("b1")));

        PatientContract.DeletionPreview preview = service.preview("pat_1", "患者申请", "admin");

        assertThat(preview.resourceCount()).isEqualTo(5);
        assertThat(preview.byKind()).containsEntry("METRIC", 2L).containsEntry("OBJECT", 1L);
        assertThat(preview.previewToken()).hasSize(16);
        assertThat(preview.status()).isEqualTo("PREVIEW");
        verify(taskMapper).insert(any(PatientDeletionTask.class));
        verify(resourceMapper, times(5)).insert(any(PatientDeletionResource.class));
    }

    @Test
    @DisplayName("令牌不对：拒绝确认，且不标记患者删除")
    void confirmRejectsWrongToken() {
        PatientDeletionTask task = task("PREVIEW", "tok");
        when(taskMapper.selectOne(any())).thenReturn(task);

        assertThatThrownBy(() -> service.confirm("pat_1", "pdt_1",
                new PatientContract.DeletionConfirmRequest("wrong", 2), "admin"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo("DELETION_TOKEN_INVALID");
        verify(lifecycle, never()).markDeleted(anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("确认后：患者标记删除 + 任务转 PENDING + 派发擦除事件")
    void confirmMarksPatientAndDispatches() {
        when(taskMapper.selectOne(any())).thenReturn(task("PREVIEW", "tok"));
        when(resourceMapper.selectList(any())).thenReturn(List.of());

        PatientContract.DeletionTaskView view = service.confirm("pat_1", "pdt_1",
                new PatientContract.DeletionConfirmRequest("tok", 2), "admin");

        verify(lifecycle).markDeleted("pat_1", 2, "admin");
        assertThat(view.status()).isEqualTo("PENDING");
        verify(taskMapper).updateById(any(PatientDeletionTask.class));
    }

    @Test
    @DisplayName("台账回读会重算哈希：内容被改过就 verified=false")
    void ledgerVerifiesHash() {
        byte[] bytes = "{\"taskId\":\"pdt_1\"}".getBytes(StandardCharsets.UTF_8);
        PatientDeletionTask task = task("COMPLETED", "tok");
        task.setLedgerKey("ledger/patient-deletion/pdt_1.json");
        task.setLedgerSha256(Ids.sha256Hex(bytes));
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(storage.get("ledger/patient-deletion/pdt_1.json")).thenReturn(bytes);

        PatientContract.LedgerView view = service.ledger("pdt_1", "admin");
        assertThat(view.verified()).isTrue();

        PatientDeletionTask tampered = task("COMPLETED", "tok");
        tampered.setLedgerKey("ledger/patient-deletion/pdt_1.json");
        tampered.setLedgerSha256("0".repeat(64));
        when(taskMapper.selectOne(any())).thenReturn(tampered);
        assertThat(service.ledger("pdt_1", "admin").verified()).isFalse();
    }

    @Test
    @DisplayName("台账未生成时明确报错，不返回空对象")
    void ledgerMissingIsExplicit() {
        when(taskMapper.selectOne(any())).thenReturn(task("PENDING", "tok"));

        assertThatThrownBy(() -> service.ledger("pdt_1", "admin"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo("DELETION_LEDGER_MISSING");
        verify(storage, never()).get(anyString());
    }

    @Test
    @DisplayName("执行时逐条擦除并写台账，最后才删主记录")
    void processErasesResourcesAndArchivesLedger() {
        PatientDeletionTask task = task("PENDING", "tok");
        when(taskMapper.selectById("pdt_1")).thenReturn(task);
        PatientDeletionResource metric = resource("METRIC", "m1", null);
        PatientDeletionResource object = resource("OBJECT", "o1", "patients/pat_1/o1.pdf");
        when(resourceMapper.selectList(any())).thenReturn(List.of(metric, object));
        when(storage.putAt(anyString(), any())).thenReturn(
                new ObjectStorage.StoredObject("ledger/patient-deletion/pdt_1.json", "a".repeat(64), 10));

        service.process("pdt_1");

        verify(metricMapper).deleteById("m1");
        verify(objectMapper).deleteById("o1");
        verify(storage).delete("patients/pat_1/o1.pdf");
        verify(storage).putAt(eq("ledger/patient-deletion/pdt_1.json"), any());
        verify(resourceMapper, times(2)).updateById(any(PatientDeletionResource.class));

        ArgumentCaptor<PatientDeletionTask> captor = ArgumentCaptor.forClass(PatientDeletionTask.class);
        verify(taskMapper, times(2)).updateById(captor.capture());
        assertThat(captor.getAllValues().get(captor.getAllValues().size() - 1).getStatus())
                .isEqualTo("COMPLETED");
    }

    // ------------------------------------------------------------ 构造辅助

    private static PatientDeletionTask task(String status, String token) {
        PatientDeletionTask task = new PatientDeletionTask();
        task.setId("pdt_1");
        task.setPatientId("pat_1");
        task.setStatus(status);
        task.setPreviewToken(token);
        task.setRequestedBy("admin");
        task.setOwner("admin");
        task.setReason("申请删除");
        task.setResourceCount(0);
        task.setCreatedAt(OffsetDateTime.now());
        return task;
    }

    private static PatientMetric metric(String id) {
        PatientMetric metric = new PatientMetric();
        metric.setId(id);
        metric.setPatientId("pat_1");
        metric.setCode("SBP");
        return metric;
    }

    private static PatientRevision revision(String id) {
        PatientRevision revision = new PatientRevision();
        revision.setId(id);
        revision.setPatientId("pat_1");
        revision.setRevision(1);
        return revision;
    }

    private static PatientObjectEntity object(String id, String key) {
        PatientObjectEntity object = new PatientObjectEntity();
        object.setId(id);
        object.setPatientId("pat_1");
        object.setObjectKey(key);
        object.setStatus("ACTIVE");
        return object;
    }

    private static PatientBatchRow batchRow(String id) {
        PatientBatchRow row = new PatientBatchRow();
        row.setId(id);
        row.setPatientId("pat_1");
        row.setBatchId("pbt_1");
        return row;
    }

    private static PatientDeletionResource resource(String kind, String key, String scope) {
        PatientDeletionResource resource = new PatientDeletionResource();
        resource.setId("pdr_" + key);
        resource.setTaskId("pdt_1");
        resource.setResourceKind(kind);
        resource.setResourceKey(key);
        resource.setScope(scope);
        resource.setErased(false);
        return resource;
    }
}
