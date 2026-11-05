package com.kangheng.healthos.service.patient;

import java.nio.charset.StandardCharsets;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.service.outbox.OutboxService;
import com.kangheng.healthos.contract.patient.PatientContract;
import com.kangheng.healthos.mapper.patient.PatientBatchMapper;
import com.kangheng.healthos.mapper.patient.PatientBatchRowMapper;
import com.kangheng.healthos.model.patient.PatientBatch;
import com.kangheng.healthos.model.patient.PatientBatchRow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PatientBatchServiceTest {

    private PatientBatchMapper batchMapper;
    private PatientBatchRowMapper rowMapper;
    private OutboxService outboxService;
    private PatientBatchService service;

    @BeforeEach
    void setUp() {
        batchMapper = mock(PatientBatchMapper.class);
        rowMapper = mock(PatientBatchRowMapper.class);
        outboxService = mock(OutboxService.class);
        service = new PatientBatchService(batchMapper, rowMapper, mock(PatientLifecycleService.class),
                outboxService, mock(org.springframework.transaction.PlatformTransactionManager.class),
                new HealthOsProperties());
    }

    @Test
    @DisplayName("CSV 提交：落批次 + 逐行落库 + 写一条 outbox 事件")
    void submitsCsv() {
        when(batchMapper.selectOne(any())).thenReturn(null);
        String csv = "external_id,name,gender,birth_date,phone\n"
                + "E-1,张三,MALE,1965-04-12,13800000000\n"
                + "E-2,\"李四\",FEMALE,1970-01-01,\n";

        PatientContract.BatchSubmitResponse response = service.submit("patients.csv",
                csv.getBytes(StandardCharsets.UTF_8), "admin");

        assertThat(response.totalRows()).isEqualTo(2);
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.reused()).isFalse();

        ArgumentCaptor<PatientBatch> batchCaptor = ArgumentCaptor.forClass(PatientBatch.class);
        verify(batchMapper).insert(batchCaptor.capture());
        assertThat(batchCaptor.getValue().getFormat()).isEqualTo("CSV");
        assertThat(batchCaptor.getValue().getTotalRows()).isEqualTo(2);

        ArgumentCaptor<PatientBatchRow> rowCaptor = ArgumentCaptor.forClass(PatientBatchRow.class);
        verify(rowMapper, times(2)).insert(rowCaptor.capture());
        assertThat(rowCaptor.getAllValues().get(0).getExternalRef()).isEqualTo("E-1");
        assertThat(rowCaptor.getAllValues().get(0).getStatus()).isEqualTo("PENDING");

        verify(outboxService).append(eq("patient_batch"), anyString(),
                eq(PatientBatchService.EVENT_IMPORT), any());
    }

    @Test
    @DisplayName("同一份文件重复上传：返回已有批次，不重复落库")
    void reusesExistingBatchForSameContent() {
        PatientBatch existing = new PatientBatch();
        existing.setId("pbt_old");
        existing.setStatus("SUCCEEDED");
        existing.setTotalRows(2);
        when(batchMapper.selectOne(any())).thenReturn(existing);

        PatientContract.BatchSubmitResponse response = service.submit("patients.csv",
                "name,gender\n张三,MALE\n".getBytes(StandardCharsets.UTF_8), "admin");

        assertThat(response.reused()).isTrue();
        assertThat(response.batchId()).isEqualTo("pbt_old");
        verify(batchMapper, never()).insert(any(PatientBatch.class));
        verify(outboxService, never()).append(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("CSV 缺少必需表头直接拒绝，避免整批静默导进去")
    void rejectsCsvWithoutRequiredHeader() {
        assertThatThrownBy(() -> service.submit("bad.csv",
                "foo,bar\n1,2\n".getBytes(StandardCharsets.UTF_8), "admin"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("JSON 数组也能导入，字段别名会被归一")
    void submitsJson() {
        when(batchMapper.selectOne(any())).thenReturn(null);
        String json = "[{\"name\":\"张三\",\"sex\":\"MALE\",\"external_id\":\"E-9\"}]";

        PatientContract.BatchSubmitResponse response = service.submit("p.json",
                json.getBytes(StandardCharsets.UTF_8), "admin");

        assertThat(response.totalRows()).isEqualTo(1);
        ArgumentCaptor<PatientBatch> captor = ArgumentCaptor.forClass(PatientBatch.class);
        verify(batchMapper).insert(captor.capture());
        assertThat(captor.getValue().getFormat()).isEqualTo("JSON");
    }
}
