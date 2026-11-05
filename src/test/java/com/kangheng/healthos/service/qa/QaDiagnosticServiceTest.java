package com.kangheng.healthos.service.qa;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.contract.qa.QaAuxiliaryContract;
import com.kangheng.healthos.mapper.qa.KnowledgeBindingMapper;
import com.kangheng.healthos.mapper.qa.QaConversationMapper;
import com.kangheng.healthos.mapper.qa.QaDiagnosticMapper;
import com.kangheng.healthos.mapper.qa.QaRunMapper;
import com.kangheng.healthos.model.qa.KnowledgeBindingEntity;
import com.kangheng.healthos.model.qa.QaConversation;
import com.kangheng.healthos.model.qa.QaDiagnostic;
import com.kangheng.healthos.service.outbox.OutboxService;
import com.kangheng.healthos.spi.retrieval.KnowledgeBase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 单路 RAG 诊断：幂等键、绑定归属、降级结论与状态机。 */
class QaDiagnosticServiceTest {

    private static final String OWNER = "admin";
    private static final String PATIENT = "pat_1";
    private static final String BINDING = "kbind_1";
    private static final String CONV = "qconv_1";

    private QaDiagnosticMapper diagnosticMapper;
    private KnowledgeBindingMapper bindingMapper;
    private QaConversationMapper conversationMapper;
    private QaRunMapper runMapper;
    private KnowledgeBase knowledgeBase;
    private OutboxService outboxService;
    private QaDiagnosticService service;
    private Map<String, QaDiagnostic> store;

    @BeforeEach
    void setUp() {
        diagnosticMapper = mock(QaDiagnosticMapper.class);
        bindingMapper = mock(KnowledgeBindingMapper.class);
        conversationMapper = mock(QaConversationMapper.class);
        runMapper = mock(QaRunMapper.class);
        knowledgeBase = mock(KnowledgeBase.class);
        outboxService = mock(OutboxService.class);
        service = new QaDiagnosticService(diagnosticMapper, bindingMapper, conversationMapper,
                runMapper, knowledgeBase, new HealthOsProperties(), outboxService);
        store = new LinkedHashMap<>();

        KnowledgeBindingEntity binding = new KnowledgeBindingEntity();
        binding.setId(BINDING);
        binding.setOwner(OWNER);
        binding.setPatientId(PATIENT);
        binding.setDatasetId("ds_1");
        binding.setStatus("ACTIVE");
        when(bindingMapper.selectById(BINDING)).thenReturn(binding);

        QaConversation conversation = new QaConversation();
        conversation.setId(CONV);
        conversation.setCreatedBy(OWNER);
        conversation.setCreatedAt(OffsetDateTime.now());
        when(conversationMapper.selectById(CONV)).thenReturn(conversation);

        when(diagnosticMapper.selectOne(any())).thenAnswer(invocation ->
                store.values().stream().findFirst().orElse(null));
        when(diagnosticMapper.selectList(any()))
                .thenAnswer(invocation -> new ArrayList<>(store.values()));
        when(diagnosticMapper.insert(any(QaDiagnostic.class))).thenAnswer(invocation -> {
            QaDiagnostic row = invocation.getArgument(0);
            store.put(row.getId(), row);
            return 1;
        });
        when(diagnosticMapper.selectById(anyString()))
                .thenAnswer(invocation -> store.get(invocation.getArgument(0)));
        when(diagnosticMapper.updateById(any(QaDiagnostic.class))).thenAnswer(invocation -> {
            QaDiagnostic patch = invocation.getArgument(0);
            QaDiagnostic row = store.get(patch.getId());
            if (row == null) {
                return 0;
            }
            if (patch.getStatus() != null) {
                row.setStatus(patch.getStatus());
            }
            if (patch.getResult() != null) {
                row.setResult(patch.getResult());
            }
            if (patch.getDraft() != null) {
                row.setDraft(patch.getDraft());
            }
            if (patch.getErrorCode() != null) {
                row.setErrorCode(patch.getErrorCode());
            }
            if (patch.getErrorMessage() != null) {
                row.setErrorMessage(patch.getErrorMessage());
            }
            if (patch.getCompletedAt() != null) {
                row.setCompletedAt(patch.getCompletedAt());
            }
            return 1;
        });
        when(knowledgeBase.retrieveDatasets(anyList(), anyString(), anyInt())).thenReturn(List.of());
    }

    private QaAuxiliaryContract.DiagnosticRequest request(String question) {
        return new QaAuxiliaryContract.DiagnosticRequest(question, 3);
    }

    @Test
    @DisplayName("缺少 Idempotency-Key 直接 422，不落任何数据")
    void missingIdempotencyKey() {
        assertThatThrownBy(() -> service.submit(OWNER, BINDING, null, request("血压趋势")))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(QaDiagnosticService.ERROR_KEY_REQUIRED));
        assertThat(store).isEmpty();
    }

    @Test
    @DisplayName("绑定不存在或不属于当前账号：404，不承认资源存在")
    void bindingOwnership() {
        KnowledgeBindingEntity foreign = new KnowledgeBindingEntity();
        foreign.setId(BINDING);
        foreign.setOwner("clinician");
        foreign.setStatus("ACTIVE");
        when(bindingMapper.selectById(BINDING)).thenReturn(foreign);
        assertThatThrownBy(() -> service.submit(OWNER, BINDING, "key-1", request("血压趋势")))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("绑定非 ACTIVE：409，诊断不该在不可用的库上跑")
    void bindingMustBeActive() {
        KnowledgeBindingEntity disabled = new KnowledgeBindingEntity();
        disabled.setId(BINDING);
        disabled.setOwner(OWNER);
        disabled.setStatus("NEEDS_REPAIR");
        when(bindingMapper.selectById(BINDING)).thenReturn(disabled);

        assertThatThrownBy(() -> service.submit(OWNER, BINDING, "key-1", request("血压趋势")))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(QaDiagnosticService.ERROR_BINDING_NOT_ACTIVE));
    }

    @Test
    @DisplayName("同 key 同参数重复提交：幂等返回同一个 run，不重复入队")
    void sameRequestIsIdempotent() {
        QaAuxiliaryContract.DiagnosticAccepted first =
                service.submit(OWNER, BINDING, "key-1", request("血压趋势"));
        QaAuxiliaryContract.DiagnosticAccepted second =
                service.submit(OWNER, BINDING, "key-1", request("血压趋势"));

        assertThat(second.runId()).isEqualTo(first.runId());
        assertThat(store).hasSize(1);
    }

    @Test
    @DisplayName("同一个 Idempotency-Key 换了问题：409，不能把两次诊断混成一次")
    void keyReuseWithDifferentParamsConflicts() {
        service.submit(OWNER, BINDING, "key-1", request("血压趋势"));
        assertThatThrownBy(() -> service.submit(OWNER, BINDING, "key-1", request("血糖趋势")))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(QaDiagnosticService.ERROR_IDEMPOTENCY_CONFLICT));
    }

    @Test
    @DisplayName("检索无命中：诊断仍是 SUCCEEDED，结论降级为 no_evidence（不抛给 Outbox 重试）")
    void noHitDegradesToNoEvidence() {
        QaAuxiliaryContract.DiagnosticAccepted accepted =
                service.submit(OWNER, BINDING, "key-1", request("血压趋势"));
        service.execute(accepted.runId());

        QaDiagnostic row = store.get(accepted.runId());
        assertThat(row.getStatus()).isEqualTo(QaDiagnosticService.STATUS_SUCCEEDED);
        assertThat(row.getResult()).contains("no_evidence").contains("no_hit");
        assertThat(row.getDraft()).contains("无命中");
    }

    @Test
    @DisplayName("检索依赖不可用：降级为 unknown，仍是 SUCCEEDED，错误写进 result")
    void unavailableDependencyDegrades() {
        when(knowledgeBase.retrieveDatasets(anyList(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("ragflow down"));
        QaAuxiliaryContract.DiagnosticAccepted accepted =
                service.submit(OWNER, BINDING, "key-1", request("血压趋势"));
        service.execute(accepted.runId());

        QaDiagnostic row = store.get(accepted.runId());
        assertThat(row.getStatus()).isEqualTo(QaDiagnosticService.STATUS_SUCCEEDED);
        assertThat(row.getResult()).contains("model_unknown_retrieval").contains("ragflow down");
    }

    @Test
    @DisplayName("执行是幂等的：已 SUCCEEDED 不再重复检索")
    void executeIsIdempotent() {
        QaAuxiliaryContract.DiagnosticAccepted accepted =
                service.submit(OWNER, BINDING, "key-1", request("血压趋势"));
        service.execute(accepted.runId());
        service.execute(accepted.runId());

        org.mockito.Mockito.verify(knowledgeBase, org.mockito.Mockito.times(1))
                .retrieveDatasets(anyList(), anyString(), anyInt());
    }

    @Test
    @DisplayName("会话维度列表与详情都按 owner 隔离")
    void listOwnership() {
        QaConversation foreign = new QaConversation();
        foreign.setId(CONV);
        foreign.setCreatedBy("clinician");
        when(conversationMapper.selectById(CONV)).thenReturn(foreign);
        assertThatThrownBy(() -> service.list(OWNER, CONV, null, 20))
                .isInstanceOf(BizException.class);
    }
}
