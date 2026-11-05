package com.kangheng.healthos.service.patient;

import java.util.List;

import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.contract.patient.PatientContract;
import com.kangheng.healthos.mapper.patient.PatientObjectMapper;
import com.kangheng.healthos.model.patient.PatientObjectEntity;
import com.kangheng.healthos.spi.storage.ObjectStorage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PatientOrphanServiceTest {

    private PatientObjectMapper objectMapper;
    private ObjectStorage storage;
    private PatientOrphanService service;

    @BeforeEach
    void setUp() {
        objectMapper = mock(PatientObjectMapper.class);
        storage = mock(ObjectStorage.class);
        service = new PatientOrphanService(objectMapper, storage, new HealthOsProperties());
    }

    private static PatientObjectEntity object(String key) {
        PatientObjectEntity entity = new PatientObjectEntity();
        entity.setObjectKey(key);
        entity.setStatus("ACTIVE");
        return entity;
    }

    @Test
    @DisplayName("扫描只读：列出未被元数据引用的 key，不删任何东西")
    void scanIsReadOnly() {
        when(storage.listKeys("patients")).thenReturn(List.of(
                "patients/pat_1/a.pdf", "patients/pat_1/b.pdf"));
        when(objectMapper.selectList(any())).thenReturn(List.of(object("patients/pat_1/a.pdf")));

        PatientContract.OrphanScan scan = service.scan();

        assertThat(scan.totalKeys()).isEqualTo(2);
        assertThat(scan.referencedKeys()).isEqualTo(1);
        assertThat(scan.orphanCount()).isEqualTo(1);
        assertThat(scan.orphans()).containsExactly("patients/pat_1/b.pdf");
        assertThat(scan.truncated()).isFalse();
        verify(storage, never()).delete(any());
    }

    @Test
    @DisplayName("清理时跳过仍在用的文件与越界 key")
    void purgeSkipsReferencedAndInvalidKeys() {
        when(objectMapper.selectList(any())).thenReturn(List.of(object("patients/pat_1/a.pdf")));

        PatientContract.OrphanPurgeResult result = service.purge(List.of(
                "patients/pat_1/a.pdf", "patients/pat_1/b.pdf", "artifacts/x.pdf", "../etc/passwd"));

        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.skipped()).contains("patients/pat_1/a.pdf", "artifacts/x.pdf", "../etc/passwd");
        verify(storage).delete("patients/pat_1/b.pdf");
        verify(storage, never()).delete("patients/pat_1/a.pdf");
        verify(storage, never()).delete("artifacts/x.pdf");
    }

    @Test
    @DisplayName("空 key 列表直接拒绝：不给\"删全部孤儿\"留口子")
    void purgeRequiresExplicitKeys() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.purge(List.of()))
                .hasMessageContaining("必须显式指定");
        verify(storage, never()).delete(any());
    }

    @Test
    @DisplayName("超过上限时标记 truncated，但计数仍然真实")
    void truncatesLargeListing() {
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (int i = 0; i < 620; i++) {
            keys.add("patients/pat_1/" + i + ".pdf");
        }
        when(storage.listKeys("patients")).thenReturn(keys);
        when(objectMapper.selectList(any())).thenReturn(List.of());

        PatientContract.OrphanScan scan = service.scan();

        assertThat(scan.orphanCount()).isEqualTo(620);
        assertThat(scan.orphans()).hasSize(500);
        assertThat(scan.truncated()).isTrue();
        verify(storage, never()).delete(anyString());
    }
}
