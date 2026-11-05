package com.kangheng.healthos.service.qa;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.contract.qa.KnowledgeBindingContract;
import com.kangheng.healthos.mapper.patient.PatientMapper;
import com.kangheng.healthos.mapper.qa.KnowledgeBindingMapper;
import com.kangheng.healthos.model.patient.Patient;
import com.kangheng.healthos.model.qa.KnowledgeBindingEntity;
import com.kangheng.healthos.spi.retrieval.KnowledgeBase;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库绑定的边界。
 *
 * <p>这组用例守的是四件事：绑不到不存在的库、重复绑不产生脏数据、解绑不删远端库、
 * 库没了不静默删除记录。最后一条最容易被写反——"自动清理失效绑定"听起来很勤快，
 * 实际上让"这个患者从哪天开始少了依据"变成不可考。
 */
class KnowledgeBindingServiceTest {

    private KnowledgeBindingMapper bindingMapper;
    private PatientMapper patientMapper;
    private KnowledgeBase knowledgeBase;
    private KnowledgeBindingService service;

    @BeforeAll
    static void initMeta() {
        for (Class<?> entity : List.of(KnowledgeBindingEntity.class, Patient.class)) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
    }

    @BeforeEach
    void setUp() {
        bindingMapper = mock(KnowledgeBindingMapper.class);
        patientMapper = mock(PatientMapper.class);
        knowledgeBase = mock(KnowledgeBase.class);
        service = new KnowledgeBindingService(bindingMapper, patientMapper, knowledgeBase);

        Patient patient = new Patient();
        patient.setId("pat_1");
        patient.setCreatedBy("alice");
        when(patientMapper.selectById("pat_1")).thenReturn(patient);
        when(knowledgeBase.name()).thenReturn("ragflow");
        when(knowledgeBase.datasets()).thenReturn(List.of(
                new KnowledgeBase.DatasetInfo("ds_1", "患者资料库", 120, 3)));
    }

    @Test
    @DisplayName("绑不存在的库 → 404，不落一条永远查不到内容的记录")
    void 绑定不存在的库被拒() {
        assertThatThrownBy(() -> service.bind("alice", "pat_1",
                new KnowledgeBindingContract.BindRequest("ds_missing", null)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getStatus().value()).isEqualTo(404));
        verify(bindingMapper, never()).insert(any(KnowledgeBindingEntity.class));
    }

    @Test
    @DisplayName("重复绑定同一个库是幂等的")
    void 重复绑定幂等() {
        KnowledgeBindingEntity existing = new KnowledgeBindingEntity();
        existing.setId("qkb_1");
        existing.setOwner("alice");
        existing.setPatientId("pat_1");
        existing.setDatasetId("ds_1");
        existing.setDatasetName("患者资料库");
        existing.setStatus("ACTIVE");
        when(bindingMapper.selectOne(any())).thenReturn(existing);

        KnowledgeBindingContract.BindingView view = service.bind("alice", "pat_1",
                new KnowledgeBindingContract.BindRequest("ds_1", "重复提交"));

        assertThat(view.id()).isEqualTo("qkb_1");
        verify(bindingMapper, never()).insert(any(KnowledgeBindingEntity.class));
    }

    @Test
    @DisplayName("解绑只删映射，不删远端数据集")
    void 解绑不删库() {
        when(bindingMapper.selectById("qkb_1")).thenReturn(entity("ACTIVE"));

        service.unbind("alice", "pat_1", "qkb_1");

        verify(bindingMapper).deleteById("qkb_1");
        verify(knowledgeBase, never()).delete(anyString());
    }

    @Test
    @DisplayName("越权解绑他人的绑定 → 404")
    void 越权解绑被拒() {
        KnowledgeBindingEntity other = entity("ACTIVE");
        other.setOwner("bob");
        when(bindingMapper.selectById("qkb_1")).thenReturn(other);

        assertThatThrownBy(() -> service.unbind("alice", "pat_1", "qkb_1"))
                .isInstanceOf(BizException.class);
        verify(bindingMapper, never()).deleteById("qkb_1");
    }

    @Test
    @DisplayName("对账发现库没了 → STALE，记录保留")
    void 库消失标为失效() {
        KnowledgeBindingEntity binding = entity("ACTIVE");
        binding.setDatasetId("ds_gone");
        when(bindingMapper.selectById("qkb_1")).thenReturn(binding);

        KnowledgeBindingContract.BindingView view = service.reconcile("alice", "pat_1", "qkb_1");

        assertThat(view.status()).isEqualTo(KnowledgeBindingService.STATUS_STALE);
        assertThat(view.lastError()).contains("ds_gone");
        verify(bindingMapper).updateById(binding);
        verify(bindingMapper, never()).deleteById(anyString());
    }

    @Test
    @DisplayName("没有绑定时不额外检索")
    void 无绑定不检索() {
        when(bindingMapper.selectList(any())).thenReturn(List.of());

        List<KnowledgeBase.RetrievedChunk> base = List.of(chunk("c1", "四君子汤"));
        List<KnowledgeBase.RetrievedChunk> merged = service.augment("alice", "pat_1", "四君子汤", base, 5);

        assertThat(merged).isSameAs(base);
        verify(knowledgeBase, never()).retrieveDatasets(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("有绑定则合并，并按内容去重（跨库 id 不同、内容相同只留一条）")
    void 合并去重() {
        when(bindingMapper.selectList(any())).thenReturn(List.of(entity("ACTIVE")));
        when(knowledgeBase.retrieveDatasets(any(), anyString(), anyInt())).thenReturn(List.of(
                // 本地切片与 RAGFlow 切片 id 不同，但正文一致——不能喂给模型两遍
                chunk("rf_c1", "四君子汤益气健脾"),
                chunk("c9", "患者既往有高血压病史")));

        List<KnowledgeBase.RetrievedChunk> base = List.of(chunk("c1", "四君子汤益气健脾"));
        List<KnowledgeBase.RetrievedChunk> merged = service.augment("alice", "pat_1", "四君子汤", base, 5);

        assertThat(merged).hasSize(2);
        assertThat(merged).extracting(KnowledgeBase.RetrievedChunk::chunkId)
                .containsExactly("c1", "c9");
    }

    @Test
    @DisplayName("患者库检索失败只降级，不能把整次问答打挂")
    void 检索失败降级() {
        when(bindingMapper.selectList(any())).thenReturn(List.of(entity("ACTIVE")));
        when(knowledgeBase.retrieveDatasets(any(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("RAGFlow 不可达"));

        List<KnowledgeBase.RetrievedChunk> base = List.of(chunk("c1", "四君子汤"));
        assertThat(service.augment("alice", "pat_1", "四君子汤", base, 5)).isSameAs(base);
    }

    @Test
    @DisplayName("合并后每条仍带自己的文档归属，不能串成同一份文档")
    void 合并保留文档归属() {
        when(bindingMapper.selectList(any())).thenReturn(List.of(entity("ACTIVE")));
        when(knowledgeBase.retrieveDatasets(any(), anyString(), anyInt())).thenReturn(List.of(
                new KnowledgeBase.RetrievedChunk("rf_c9", "doc_note", "患者既往有高血压病史", null, null, null, 0.6)));

        List<KnowledgeBase.RetrievedChunk> base = List.of(
                new KnowledgeBase.RetrievedChunk("c1", "doc_book", "四君子汤益气健脾", 3, 100, 108, 0.9));
        List<KnowledgeBase.RetrievedChunk> merged = service.augment("alice", "pat_1", "高血压用药", base, 5);

        assertThat(merged).extracting(KnowledgeBase.RetrievedChunk::documentId)
                .containsExactly("doc_book", "doc_note");
    }

    private KnowledgeBindingEntity entity(String status) {
        KnowledgeBindingEntity entity = new KnowledgeBindingEntity();
        entity.setId("qkb_1");
        entity.setOwner("alice");
        entity.setPatientId("pat_1");
        entity.setDatasetId("ds_1");
        entity.setDatasetName("患者资料库");
        entity.setStatus(status);
        entity.setCreatedAt(OffsetDateTime.now());
        return entity;
    }

    private KnowledgeBase.RetrievedChunk chunk(String id, String content) {
        return new KnowledgeBase.RetrievedChunk(id, content, null, null, null, 0.8);
    }
}
