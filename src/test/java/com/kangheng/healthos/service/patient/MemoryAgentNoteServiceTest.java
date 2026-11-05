package com.kangheng.healthos.service.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.contract.patient.MemoryAgentNoteContract;
import com.kangheng.healthos.mapper.patient.MemoryAgentNoteMapper;
import com.kangheng.healthos.mapper.patient.PatientFollowupMapper;
import com.kangheng.healthos.mapper.patient.PatientMapper;
import com.kangheng.healthos.model.patient.MemoryAgentNote;
import com.kangheng.healthos.model.patient.Patient;
import com.kangheng.healthos.model.patient.PatientFollowup;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/**
 * 记忆 Agent 摘录的边界。
 *
 * <p>三条不能让步：候选覆盖全部分类且逐字摘录、operationId 幂等、
 * 发布不改正式记忆版本（凭据落 note 表）。特别是"quote 非逐字摘录被拒"——
 * 生成器现在总是截前缀，守卫看似多余，但它是防止以后换成模型生成时悄悄把
 * 改写过的话当成原文写进记忆的唯一一道闸。
 */
class MemoryAgentNoteServiceTest {

    private MemoryAgentNoteMapper noteMapper;
    private PatientMapper patientMapper;
    private PatientFollowupMapper followupMapper;
    private PatientMemoryService memoryService;
    private MemoryAgentNoteService service;

    @BeforeAll
    static void initMybatisMeta() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), MemoryAgentNote.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PatientFollowup.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Patient.class);
    }

    @BeforeEach
    void setUp() {
        noteMapper = mock(MemoryAgentNoteMapper.class);
        patientMapper = mock(PatientMapper.class);
        followupMapper = mock(PatientFollowupMapper.class);
        memoryService = mock(PatientMemoryService.class);
        service = new MemoryAgentNoteService(noteMapper, patientMapper, followupMapper, memoryService);

        Patient patient = new Patient();
        patient.setId("pat_1");
        patient.setCreatedBy("alice");
        when(patientMapper.selectById("pat_1")).thenReturn(patient);
        when(memoryService.currentId("pat_1")).thenReturn("pmem_1");
    }

    /** 三类互异的随访原文，覆盖"随访全部分类"。 */
    private static List<MemoryAgentNoteContract.AgentEntry> entries() {
        return List.of(
                new MemoryAgentNoteContract.AgentEntry("diet", "早餐吃了燕麦和牛奶，感觉不错"),
                new MemoryAgentNoteContract.AgentEntry("symptoms", "今天没有头晕，血压自测 125/80"),
                new MemoryAgentNoteContract.AgentEntry("exercise", "晚饭后散步三十分钟，微微出汗"));
    }

    private static MemoryAgentNoteContract.AgentFollowupRequest request(String operationId) {
        return new MemoryAgentNoteContract.AgentFollowupRequest(
                operationId, "请生成随访摘录", null, entries());
    }

    /** 先跑一次 submit 并拿到落库的那行（供 publish 回查用）。 */
    private MemoryAgentNote submitOnce() {
        when(noteMapper.selectOne(any())).thenReturn(null);
        service.submit("alice", "pat_1", request("op_abcdefgh"));
        ArgumentCaptor<MemoryAgentNote> captor = ArgumentCaptor.forClass(MemoryAgentNote.class);
        verify(noteMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------------ 候选生成

    @Test
    @DisplayName("候选生成覆盖全部分类：observations 逐条对应 entryIndex 且 quote 是逐字原文")
    void candidateCoversAllCategories() {
        when(noteMapper.selectOne(any())).thenReturn(null);

        MemoryAgentNoteContract.NoteView view = service.submit("alice", "pat_1", request("op_abcdefgh"));

        assertThat(view.status()).isEqualTo(MemoryAgentNoteService.STATUS_PENDING);
        assertThat(view.observations()).hasSize(entries().size());
        assertThat(view.observations())
                .extracting(MemoryAgentNoteContract.Observation::entryIndex)
                .containsExactly(0, 1, 2);
        for (int i = 0; i < entries().size(); i++) {
            assertThat(entries().get(i).content()).contains(view.observations().get(i).quote());
        }

        ArgumentCaptor<MemoryAgentNote> captor = ArgumentCaptor.forClass(MemoryAgentNote.class);
        verify(noteMapper).insert(captor.capture());
        // observations 列是来源快照 JSON 文档：冻结了 entries 与 agentNotes
        Map<String, Object> doc = Json.readMap(captor.getValue().getObservations());
        assertThat(doc).containsKey("observations");
        assertThat(doc).containsKey("entries");
        assertThat(doc).containsKey("agentNotes");
        // PENDING 绝不生成记忆版本
        verify(memoryService, never()).publish(any(), any());
    }

    @Test
    @DisplayName("quote 非逐字摘录被拒：MEMORY_AGENT_EVIDENCE_INVALID")
    void rejectsNonVerbatimQuote() {
        List<MemoryAgentNoteContract.Observation> bad = List.of(
                new MemoryAgentNoteContract.Observation(0, entries().get(0).content().substring(0, 5)),
                new MemoryAgentNoteContract.Observation(1, "这段改写过的话根本不在原文里"),
                new MemoryAgentNoteContract.Observation(2, entries().get(2).content().substring(0, 5)));

        assertThatThrownBy(() -> service.assertValidObservations(entries(), bad))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode())
                        .isEqualTo(MemoryAgentNoteService.ERROR_EVIDENCE_INVALID));
    }

    // ------------------------------------------------------------ 幂等

    @Test
    @DisplayName("重复 operationId 幂等：返回既有视图，不重复 insert")
    void duplicateOperationIdIsIdempotent() {
        when(noteMapper.selectOne(any())).thenReturn(null);
        MemoryAgentNoteContract.NoteView first =
                service.submit("alice", "pat_1", request("op_abcdefgh"));
        ArgumentCaptor<MemoryAgentNote> captor = ArgumentCaptor.forClass(MemoryAgentNote.class);
        verify(noteMapper).insert(captor.capture());
        when(noteMapper.selectOne(any())).thenReturn(captor.getValue());

        MemoryAgentNoteContract.NoteView second =
                service.submit("alice", "pat_1", request("op_abcdefgh"));

        assertThat(second.noteId()).isEqualTo(first.noteId());
        assertThat(second.candidateHash()).isEqualTo(first.candidateHash());
        verify(noteMapper, times(1)).insert(any(MemoryAgentNote.class));
    }

    // ------------------------------------------------------------ 发布

    @Test
    @DisplayName("publish hash 不一致 → 409 CANDIDATE_CONFLICT")
    void publishRejectsWrongCandidateHash() {
        MemoryAgentNote note = submitOnce();
        when(noteMapper.selectOne(any())).thenReturn(note);

        assertThatThrownBy(() -> service.publish("alice", "pat_1", note.getId(),
                new MemoryAgentNoteContract.PublishRequest("wrong-hash")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode())
                        .isEqualTo(MemoryAgentNoteService.ERROR_CANDIDATE_CONFLICT));

        verify(noteMapper, never()).updateById(any(MemoryAgentNote.class));
    }

    @Test
    @DisplayName("publish 幂等：已 PUBLISHED 且 hash 相同直接返回既有视图，只发布一次")
    void publishIsIdempotent() {
        MemoryAgentNote note = submitOnce();
        when(noteMapper.selectOne(any())).thenReturn(note);
        String hash = note.getCandidateHash();

        MemoryAgentNoteContract.NoteView published = service.publish("alice", "pat_1",
                note.getId(), new MemoryAgentNoteContract.PublishRequest(hash));
        assertThat(published.status()).isEqualTo(MemoryAgentNoteService.STATUS_PUBLISHED);
        assertThat(note.getMemoryId()).isEqualTo("pmem_1");  // 发布凭据 = 当前记忆版本 id

        MemoryAgentNoteContract.NoteView again = service.publish("alice", "pat_1",
                note.getId(), new MemoryAgentNoteContract.PublishRequest(hash));
        assertThat(again.status()).isEqualTo(MemoryAgentNoteService.STATUS_PUBLISHED);
        assertThat(again.noteId()).isEqualTo(published.noteId());

        verify(noteMapper, times(1)).updateById(any(MemoryAgentNote.class));
        // 关键：发布不改正式记忆版本
        verify(memoryService, never()).publish(any(), any());
    }

    @Test
    @DisplayName("publish 时记忆版本漂移 → 409 MEMORY_AGENT_SOURCE_CHANGED")
    void publishRejectsSourceChanged() {
        MemoryAgentNote note = submitOnce();
        when(noteMapper.selectOne(any())).thenReturn(note);
        when(memoryService.currentId("pat_1")).thenReturn("pmem_2");  // 期间发布了新版本

        assertThatThrownBy(() -> service.publish("alice", "pat_1", note.getId(),
                new MemoryAgentNoteContract.PublishRequest(note.getCandidateHash())))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode())
                        .isEqualTo(MemoryAgentNoteService.ERROR_SOURCE_CHANGED));

        verify(noteMapper, never()).updateById(any(MemoryAgentNote.class));
    }

    // ------------------------------------------------------------ 归属隔离

    @Test
    @DisplayName("跨账号 404：患者不属当前 owner，查不到返回 404 而不是 403")
    void otherOwnerIsNotFound() {
        assertThatThrownBy(() -> service.list("bob", "pat_1"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        verify(noteMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("note 不存在 → 404 MEMORY_AGENT_NOTE_NOT_FOUND")
    void missingNoteIsNotFound() {
        when(noteMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.publish("alice", "pat_1", "mnote_missing",
                new MemoryAgentNoteContract.PublishRequest("any-hash")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode())
                        .isEqualTo(MemoryAgentNoteService.ERROR_NOTE_NOT_FOUND));
    }
}
