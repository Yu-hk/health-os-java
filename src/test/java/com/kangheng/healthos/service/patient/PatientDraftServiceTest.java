package com.kangheng.healthos.service.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.contract.patient.PatientDraftContract;
import com.kangheng.healthos.mapper.patient.PatientIntakeExamMapper;
import com.kangheng.healthos.mapper.patient.PatientReviewDraftMapper;
import com.kangheng.healthos.model.patient.PatientIntakeExam;
import com.kangheng.healthos.model.patient.PatientReviewDraft;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/**
 * 审阅草稿的边界。
 *
 * <p>核心主张：<b>服务端草稿绝不改动临床修订与确认状态</b>。本组用例守的是乐观锁、
 * 白名单、丢响应重试幂等，以及"修订冲突时草稿保留"——最后一条尤其容易 regress：
 * 若实现图省事把草稿清掉，录入员核对到一半的工作就无声消失了。
 */
class PatientDraftServiceTest {

    private PatientIntakeExamMapper examMapper;
    private PatientReviewDraftMapper draftMapper;
    private PatientDraftService service;

    @BeforeAll
    static void initMybatisMeta() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PatientIntakeExam.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PatientReviewDraft.class);
    }

    @BeforeEach
    void setUp() {
        examMapper = mock(PatientIntakeExamMapper.class);
        draftMapper = mock(PatientReviewDraftMapper.class);
        service = new PatientDraftService(examMapper, draftMapper);
    }

    /** basic scope 的合法 payload：唯一允许的 key 是 basic_info，值必须是 Map。 */
    private static Map<String, Object> basicPayload() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("name", "张三");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("basic_info", inner);
        return payload;
    }

    private static PatientIntakeExam exam(int revision) {
        PatientIntakeExam exam = new PatientIntakeExam();
        exam.setId("pie_1");
        exam.setOwner("doctor");
        exam.setRevision(revision);
        return exam;
    }

    private static PatientReviewDraft draft(int version, int baseRevision, String payload) {
        PatientReviewDraft draft = new PatientReviewDraft();
        draft.setId("prd_1");
        draft.setOwner("doctor");
        draft.setExamId("pie_1");
        draft.setScope("basic");
        draft.setVersion(version);
        draft.setBaseRevision(baseRevision);
        draft.setPayload(payload);
        draft.setUpdatedAt(OffsetDateTime.parse("2026-11-01T10:00:00+08:00"));
        return draft;
    }

    // ------------------------------------------------------------ 首次 PUT

    @Test
    @DisplayName("首次 PUT 隐式创建：expectedVersion=0 → version=1，并落 owner/scope/baseRevision")
    void firstPutCreatesDraft() {
        when(examMapper.selectOne(any())).thenReturn(exam(2));
        when(draftMapper.selectOne(any())).thenReturn(null);

        PatientDraftContract.DraftResponse response = service.put("pie_1", "basic",
                new PatientDraftContract.PutDraftRequest(2, 0, basicPayload()), "doctor");

        assertThat(response.draft()).isNotNull();
        assertThat(response.draft().version()).isEqualTo(1);
        assertThat(response.draft().baseRevision()).isEqualTo(2);
        assertThat(response.draft().payload()).isEqualTo(basicPayload());
        ArgumentCaptor<PatientReviewDraft> captor = ArgumentCaptor.forClass(PatientReviewDraft.class);
        verify(draftMapper).insert(captor.capture());
        assertThat(captor.getValue().getOwner()).isEqualTo("doctor");
        assertThat(captor.getValue().getScope()).isEqualTo("basic");
        assertThat(captor.getValue().getVersion()).isEqualTo(1);
        // 草稿只 insert，绝不 update 录入单（临床修订/确认状态不被改动）
        verify(examMapper, never()).updateById(any(PatientIntakeExam.class));
    }

    // ------------------------------------------------------------ 白名单

    @Test
    @DisplayName("payload 白名单拒绝：basic 不允许 indicators，不落库")
    void rejectsPayloadKeyOutsideWhitelist() {
        when(examMapper.selectOne(any())).thenReturn(exam(2));
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("indicators", Map.of("sbp", 130));

        assertThatThrownBy(() -> service.put("pie_1", "basic",
                new PatientDraftContract.PutDraftRequest(2, 0, bad), "doctor"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不允许");

        verify(draftMapper, never()).insert(any(PatientReviewDraft.class));
    }

    @Test
    @DisplayName("payload 白名单值必须是 Map：塞字符串直接拒绝")
    void rejectsNonObjectPayloadValue() {
        when(examMapper.selectOne(any())).thenReturn(exam(2));
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("basic_info", "not-an-object");

        assertThatThrownBy(() -> service.put("pie_1", "basic",
                new PatientDraftContract.PutDraftRequest(2, 0, bad), "doctor"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("JSON object");

        verify(draftMapper, never()).insert(any(PatientReviewDraft.class));
    }

    // ------------------------------------------------------------ 乐观锁

    @Test
    @DisplayName("version 冲突：当前 version != expectedVersion → 409 DRAFT_VERSION_CONFLICT")
    void versionConflictIsRejected() {
        when(examMapper.selectOne(any())).thenReturn(exam(2));
        // 既有 version=3，请求 expectedVersion=1：不是一次合法重试，直接版本冲突
        when(draftMapper.selectOne(any())).thenReturn(draft(3, 2, Json.write(basicPayload())));

        assertThatThrownBy(() -> service.put("pie_1", "basic",
                new PatientDraftContract.PutDraftRequest(2, 1, basicPayload()), "doctor"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode())
                        .isEqualTo(PatientDraftService.ERROR_VERSION_CONFLICT));

        verify(draftMapper, never()).insert(any(PatientReviewDraft.class));
        verify(draftMapper, never()).updateById(any(PatientReviewDraft.class));
    }

    @Test
    @DisplayName("baseRevision 冲突：409 DRAFT_REVISION_CONFLICT 且草稿保留，不清空")
    void revisionConflictKeepsDraft() {
        when(examMapper.selectOne(any())).thenReturn(exam(5));  // 期间录入单 revision 从 2 涨到 5
        PatientReviewDraft existing = draft(1, 2, Json.write(basicPayload()));
        when(draftMapper.selectOne(any())).thenReturn(existing);

        assertThatThrownBy(() -> service.put("pie_1", "basic",
                new PatientDraftContract.PutDraftRequest(2, 1, basicPayload()), "doctor"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode())
                        .isEqualTo(PatientDraftService.ERROR_REVISION_CONFLICT));

        // 草稿一行都不能动：不删、不插、不更新
        verify(draftMapper, never()).delete(any());
        verify(draftMapper, never()).insert(any(PatientReviewDraft.class));
        verify(draftMapper, never()).updateById(any(PatientReviewDraft.class));
        assertThat(existing.getVersion()).isEqualTo(1);
        assertThat(existing.getPayload()).isEqualTo(Json.write(basicPayload()));
    }

    // ------------------------------------------------------------ 丢响应重试

    @Test
    @DisplayName("丢响应重试幂等：version==expectedVersion+1 且 payload/baseRevision 相同，直接返回既有")
    void lostResponseRetryIsIdempotent() {
        when(examMapper.selectOne(any())).thenReturn(exam(2));
        // 第一次 PUT 已落库（version=1），响应丢了，客户端拿 expectedVersion=0 重发
        PatientReviewDraft existing = draft(1, 2, Json.write(basicPayload()));
        when(draftMapper.selectOne(any())).thenReturn(existing);

        PatientDraftContract.DraftResponse response = service.put("pie_1", "basic",
                new PatientDraftContract.PutDraftRequest(2, 0, basicPayload()), "doctor");

        // 不能报 DRAFT_VERSION_CONFLICT，也不能把 version 推到 2
        assertThat(response.draft().version()).isEqualTo(1);
        verify(draftMapper, never()).insert(any(PatientReviewDraft.class));
        verify(draftMapper, never()).updateById(any(PatientReviewDraft.class));
        assertThat(existing.getVersion()).isEqualTo(1);
    }

    // ------------------------------------------------------------ 归属隔离

    @Test
    @DisplayName("跨账号 404：exam 不属于当前 owner，查不到返回 404 而不是 403")
    void otherOwnerIsNotFound() {
        when(examMapper.selectOne(any())).thenReturn(null);  // 按 owner 过滤查不到

        assertThatThrownBy(() -> service.get("pie_1", "basic", "doctor"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        verify(draftMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("非法 scope 直接 400")
    void rejectsUnknownScope() {
        assertThatThrownBy(() -> service.get("pie_1", "unknown", "doctor"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ------------------------------------------------------------ DELETE 幂等

    @Test
    @DisplayName("DELETE 幂等：草稿不存在也返回 draft:null，重复删除仍是 null")
    void deleteIsIdempotent() {
        when(examMapper.selectOne(any())).thenReturn(exam(2));
        when(draftMapper.delete(any())).thenReturn(0);  // 没有可删的行

        assertThat(service.delete("pie_1", "basic", "doctor").draft()).isNull();
        assertThat(service.delete("pie_1", "basic", "doctor").draft()).isNull();

        verify(draftMapper, times(2)).delete(any());
        // DELETE 也要先校验 exam 归属
        verify(examMapper, times(2)).selectOne(any());
    }
}
