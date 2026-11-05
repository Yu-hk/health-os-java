package com.kangheng.healthos.service.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.contract.patient.PatientMemoryContract;
import com.kangheng.healthos.mapper.patient.PatientFollowupMapper;
import com.kangheng.healthos.mapper.patient.PatientMemoryJobMapper;
import com.kangheng.healthos.model.patient.Patient;
import com.kangheng.healthos.model.patient.PatientFollowup;
import com.kangheng.healthos.model.patient.PatientMemoryJob;
import com.kangheng.healthos.model.patient.PatientMemoryState;
import com.kangheng.healthos.model.patient.PatientMemoryVersion;
import com.kangheng.healthos.service.outbox.OutboxService;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 随访提交与任务状态机的边界。 */
class PatientMemoryJobServiceTest {

    private PatientMemoryJobMapper jobMapper;
    private PatientFollowupMapper followupMapper;
    private OutboxService outboxService;
    private PatientMemoryService memoryService;
    private PatientMemoryJobService service;

    @BeforeAll
    static void initMeta() {
        for (Class<?> entity : List.of(Patient.class, PatientFollowup.class,
                PatientMemoryJob.class, PatientMemoryState.class, PatientMemoryVersion.class)) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
    }

    @BeforeEach
    void setUp() {
        jobMapper = mock(PatientMemoryJobMapper.class);
        followupMapper = mock(PatientFollowupMapper.class);
        outboxService = mock(OutboxService.class);
        memoryService = mock(PatientMemoryService.class);
        service = new PatientMemoryJobService(jobMapper, followupMapper, outboxService, memoryService);
    }

    @Test
    @DisplayName("提交随访返回 QUEUED 任务，原文逐字落库并投递 Outbox")
    void submitFollowup() {
        PatientMemoryContract.Accepted accepted = service.submitFollowup(
                "alice", "pat_1", request("op-followup-001", "今天  晨起头晕"));

        assertThat(accepted.job().status()).isEqualTo(PatientMemoryJobService.STATUS_QUEUED);
        assertThat(accepted.job().kind()).isEqualTo(PatientMemoryJobService.KIND_FOLLOWUP);
        assertThat(accepted.job().requestSequence()).isEqualTo(1);

        ArgumentCaptor<PatientFollowup> captor = ArgumentCaptor.forClass(PatientFollowup.class);
        verify(followupMapper).insert(captor.capture());
        List<PatientMemoryContract.FeedbackEntry> entries =
                Json.readList(captor.getValue().getEntries(), PatientMemoryContract.FeedbackEntry.class);
        assertThat(entries).hasSize(1);
        // 连续空格是原文，不能被 trim
        assertThat(entries.get(0).content()).isEqualTo("今天  晨起头晕");
        verify(outboxService).append(eq("patient_memory"), anyString(),
                eq(PatientMemoryJobService.EVENT_PUBLISH), any());
    }

    @Test
    @DisplayName("同一 operationId 重复提交幂等返回同一任务，不产生第二条随访")
    void duplicateOperationIsIdempotent() {
        PatientMemoryJob existing = job("pmjob_1", PatientMemoryJobService.STATUS_QUEUED);
        existing.setRequestHash(followupHash("今天  晨起头晕"));
        when(jobMapper.selectOne(any())).thenReturn(existing);

        PatientMemoryContract.Accepted accepted = service.submitFollowup(
                "alice", "pat_1", request("op-followup-001", "今天  晨起头晕"));

        assertThat(accepted.job().id()).isEqualTo("pmjob_1");
        verify(jobMapper, never()).insert(any(PatientMemoryJob.class));
        verify(followupMapper, never()).insert(any(PatientFollowup.class));
    }

    @Test
    @DisplayName("同一 operationId 换内容报冲突，不静默复用旧任务")
    void sameOperationDifferentBodyConflicts() {
        PatientMemoryJob existing = job("pmjob_1", PatientMemoryJobService.STATUS_QUEUED);
        existing.setRequestHash("a-completely-different-hash");
        when(jobMapper.selectOne(any())).thenReturn(existing);

        assertThatThrownBy(() -> service.submitFollowup(
                "alice", "pat_1", request("op-followup-001", "另一段内容")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode())
                        .isEqualTo("MEMORY_OPERATION_CONFLICT"));
    }

    @Test
    @DisplayName("分类重复 / 未知分类 / 控制字符 / 空内容一律拒绝")
    void invalidEntriesAreRejected() {
        List<PatientMemoryContract.FeedbackEntry> duplicated = List.of(
                new PatientMemoryContract.FeedbackEntry("diet", "早餐"),
                new PatientMemoryContract.FeedbackEntry("diet", "晚餐"));
        assertThatThrownBy(() -> service.submitFollowup("alice", "pat_1",
                request("op-followup-002", duplicated))).isInstanceOf(BizException.class);

        assertThatThrownBy(() -> service.submitFollowup("alice", "pat_1",
                request("op-followup-003", List.of(
                        new PatientMemoryContract.FeedbackEntry("unknown", "内容")))))
                .isInstanceOf(BizException.class);

        assertThatThrownBy(() -> service.submitFollowup("alice", "pat_1",
                request("op-followup-004", "含控制字符\u0001")))
                .isInstanceOf(BizException.class);

        assertThatThrownBy(() -> service.submitFollowup("alice", "pat_1",
                request("op-followup-005", "   ")))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("未来时间、非法 operationId 都拦在提交阶段")
    void invalidTimeAndOperationId() {
        assertThatThrownBy(() -> service.submitFollowup("alice", "pat_1",
                new PatientMemoryContract.FollowupRequest("op-followup-006", null,
                        OffsetDateTime.now(ZoneOffset.UTC).plusDays(1),
                        List.of(new PatientMemoryContract.FeedbackEntry("diet", "早餐")))))
                .isInstanceOf(BizException.class);

        assertThatThrownBy(() -> service.submitRefresh("alice", "pat_1",
                new PatientMemoryContract.MemoryRequest("bad id!", null)))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("客户端拿着过期版本提交时，冲突由版本服务抛出")
    void staleVersionPropagates() {
        doThrow(BizException.conflict("版本已变化")).when(memoryService)
                .assertExpected(eq("alice"), eq("pat_1"), any());
        assertThatThrownBy(() -> service.submitRefresh("alice", "pat_1",
                new PatientMemoryContract.MemoryRequest("op-refresh-001", "pmem_old")))
                .isInstanceOf(BizException.class);
        verify(jobMapper, never()).insert(any(PatientMemoryJob.class));
    }

    @Test
    @DisplayName("显式刷新不新增随访，只投一次发布事件")
    void refreshHasNoFollowup() {
        PatientMemoryContract.Accepted accepted = service.submitRefresh("alice", "pat_1",
                new PatientMemoryContract.MemoryRequest("op-refresh-002", null));
        assertThat(accepted.job().kind()).isEqualTo(PatientMemoryJobService.KIND_REFRESH);
        assertThat(accepted.job().feedbackId()).isNull();
        verify(followupMapper, never()).insert(any(PatientFollowup.class));
        verify(outboxService, times(1)).append(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("执行成功标 SUCCEEDED 并记下产出的版本 ID")
    void executeSucceeds() {
        PatientMemoryJob job = job("pmjob_1", PatientMemoryJobService.STATUS_QUEUED);
        when(jobMapper.selectById("pmjob_1")).thenReturn(job);
        PatientMemoryVersion version = new PatientMemoryVersion();
        version.setId("pmem_new");
        when(memoryService.publish("alice", "pat_1")).thenReturn(version);

        service.execute("pmjob_1");

        assertThat(job.getStatus()).isEqualTo(PatientMemoryJobService.STATUS_SUCCEEDED);
        assertThat(job.getResultMemoryId()).isEqualTo("pmem_new");
        assertThat(job.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("执行失败保留 FAILED 记录并写 error，异常继续抛给 Outbox 重试")
    void executeFailureIsVisible() {
        PatientMemoryJob job = job("pmjob_1", PatientMemoryJobService.STATUS_QUEUED);
        when(jobMapper.selectById("pmjob_1")).thenReturn(job);
        when(memoryService.publish("alice", "pat_1"))
                .thenThrow(new IllegalStateException("来源修订缺失"));

        assertThatThrownBy(() -> service.execute("pmjob_1"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(job.getStatus()).isEqualTo(PatientMemoryJobService.STATUS_FAILED);
        assertThat(job.getError()).contains("来源修订缺失");
    }

    @Test
    @DisplayName("已取消/已成功的任务被 Outbox 重放时幂等跳过")
    void executeIsIdempotentForTerminalStates() {
        PatientMemoryJob cancelled = job("pmjob_1", PatientMemoryJobService.STATUS_CANCELLED);
        when(jobMapper.selectById("pmjob_1")).thenReturn(cancelled);

        service.execute("pmjob_1");

        verify(memoryService, never()).publish(anyString(), anyString());
        assertThat(cancelled.getStatus()).isEqualTo(PatientMemoryJobService.STATUS_CANCELLED);
    }

    @Test
    @DisplayName("已成功发布的任务不能取消——历史不可撤销")
    void cannotCancelSucceededJob() {
        PatientMemoryJob succeeded = job("pmjob_1", PatientMemoryJobService.STATUS_SUCCEEDED);
        when(jobMapper.selectById("pmjob_1")).thenReturn(succeeded);
        assertThatThrownBy(() -> service.cancel("alice", "pat_1", "pmjob_1"))
                .isInstanceOf(BizException.class);
    }

    // ------------------------------------------------------------ 辅助

    /** 固定过去时间：让两次构造的 requestHash 完全一致，幂等用例才有意义。 */
    private static final OffsetDateTime OCCURRED = OffsetDateTime.parse("2020-01-01T09:00:00Z");

    private PatientMemoryContract.FollowupRequest request(String operationId, String content) {
        return request(operationId, List.of(new PatientMemoryContract.FeedbackEntry("symptoms", content)));
    }

    private PatientMemoryContract.FollowupRequest request(
            String operationId, List<PatientMemoryContract.FeedbackEntry> entries) {
        return new PatientMemoryContract.FollowupRequest(operationId, null, OCCURRED, entries);
    }

    /** 与生产 submitFollowup 的 requestHash 口径一致：kind + occurredAt + entries。 */
    private String followupHash(String content) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("kind", PatientMemoryJobService.KIND_FOLLOWUP);
        body.put("occurredAt", OCCURRED.toString());
        body.put("entries", List.of(new PatientMemoryContract.FeedbackEntry("symptoms", content)));
        return com.kangheng.healthos.common.Ids.sha256Hex(Json.write(body));
    }

    private PatientMemoryJob job(String id, String status) {
        PatientMemoryJob job = new PatientMemoryJob();
        job.setId(id);
        job.setOwner("alice");
        job.setPatientId("pat_1");
        job.setOperationId("op-followup-001");
        job.setRequestSequence(1);
        job.setRequestHash("hash");
        job.setKind(PatientMemoryJobService.KIND_FOLLOWUP);
        job.setStatus(status);
        job.setAttemptCount(0);
        return job;
    }
}
