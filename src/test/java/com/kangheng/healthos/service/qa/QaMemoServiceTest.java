package com.kangheng.healthos.service.qa;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.contract.qa.QaAuxiliaryContract;
import com.kangheng.healthos.mapper.patient.PatientMapper;
import com.kangheng.healthos.mapper.qa.QaConversationMapper;
import com.kangheng.healthos.mapper.qa.QaMemoMapper;
import com.kangheng.healthos.mapper.qa.QaMessageMapper;
import com.kangheng.healthos.mapper.qa.QaRunMapper;
import com.kangheng.healthos.model.patient.Patient;
import com.kangheng.healthos.model.qa.QaConversation;
import com.kangheng.healthos.model.qa.QaMemo;
import com.kangheng.healthos.model.qa.QaMessage;
import com.kangheng.healthos.model.qa.QaRun;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** QA 书签：显式收藏、幂等、完成态校验、归属隔离与分页。 */
class QaMemoServiceTest {

    private static final String OWNER = "admin";
    private static final String PATIENT = "pat_1";
    private static final String CONV = "qconv_1";
    private static final String MESSAGE = "qmsg_1";

    private QaMemoMapper memoMapper;
    private QaMessageMapper messageMapper;
    private QaConversationMapper conversationMapper;
    private QaRunMapper runMapper;
    private PatientMapper patientMapper;
    private QaMemoService service;
    private Map<String, QaMemo> store;

    @BeforeEach
    void setUp() {
        memoMapper = mock(QaMemoMapper.class);
        messageMapper = mock(QaMessageMapper.class);
        conversationMapper = mock(QaConversationMapper.class);
        runMapper = mock(QaRunMapper.class);
        patientMapper = mock(PatientMapper.class);
        service = new QaMemoService(memoMapper, messageMapper, conversationMapper,
                runMapper, patientMapper);
        store = new LinkedHashMap<>();

        QaConversation conversation = new QaConversation();
        conversation.setId(CONV);
        conversation.setCreatedBy(OWNER);
        conversation.setCreatedAt(OffsetDateTime.now());
        when(conversationMapper.selectById(CONV)).thenReturn(conversation);

        QaRun run = new QaRun();
        run.setId("qrun_1");
        run.setConversationId(CONV);
        run.setPatientId(PATIENT);
        run.setQuestion("最近血压怎么样？");
        run.setStatus("SUCCEEDED");
        run.setCreatedAt(OffsetDateTime.now());
        when(runMapper.selectOne(any())).thenReturn(run);

        Patient patient = new Patient();
        patient.setId(PATIENT);
        patient.setCreatedBy(OWNER);
        when(patientMapper.selectById(PATIENT)).thenReturn(patient);

        when(memoMapper.selectOne(any())).thenAnswer(invocation ->
                store.values().stream().findFirst().orElse(null));
        when(memoMapper.insert(any(QaMemo.class))).thenAnswer(invocation -> {
            QaMemo memo = invocation.getArgument(0);
            store.put(memo.getId(), memo);
            return 1;
        });
        when(memoMapper.selectById(anyString()))
                .thenAnswer(invocation -> store.get(invocation.getArgument(0)));
        when(memoMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(store.values()));
    }

    private QaMessage message(String role, String status, String content) {
        QaMessage message = new QaMessage();
        message.setId(MESSAGE);
        message.setConversationId(CONV);
        message.setRole(role);
        message.setStatus(status);
        message.setContent(content);
        message.setCitations("[{\"documentId\":\"doc_1\",\"chunkId\":\"chunk_1\"}]");
        message.setCreatedAt(OffsetDateTime.now());
        return message;
    }

    @Test
    @DisplayName("显式收藏一条已完成的助手答案：冻结问题、正文哈希与引用快照")
    void createMemo() {
        when(messageMapper.selectById(MESSAGE)).thenReturn(message("assistant", "OK", "建议规律作息。"));

        QaAuxiliaryContract.MemoCreated created = service.create(OWNER, MESSAGE);

        assertThat(created.memoId()).startsWith("qmem_");
        assertThat(created.patientId()).isEqualTo(PATIENT);
        QaMemo memo = store.get(created.memoId());
        assertThat(memo.getQuestion()).isEqualTo("最近血压怎么样？");
        assertThat(memo.getContent()).isEqualTo("建议规律作息。");
        assertThat(memo.getContentHash()).hasSize(64);
        assertThat(memo.getEvidenceSnapshot()).contains("chunk_1");
    }

    @Test
    @DisplayName("重复收藏同一条消息幂等：不新建第二条")
    void duplicateIsIdempotent() {
        when(messageMapper.selectById(MESSAGE)).thenReturn(message("assistant", "OK", "建议规律作息。"));
        QaAuxiliaryContract.MemoCreated first = service.create(OWNER, MESSAGE);
        QaAuxiliaryContract.MemoCreated second = service.create(OWNER, MESSAGE);

        assertThat(second.memoId()).isEqualTo(first.memoId());
        assertThat(store).hasSize(1);
    }

    @Test
    @DisplayName("只能收藏已完成的助手消息：用户消息 / 未完成状态都拒绝")
    void onlyCompletedAssistantMessage() {
        when(messageMapper.selectById(MESSAGE)).thenReturn(message("user", "OK", "我最近睡不好"));
        assertThatThrownBy(() -> service.create(OWNER, MESSAGE))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(QaMemoService.ERROR_ANSWER_NOT_COMPLETE));

        when(messageMapper.selectById(MESSAGE)).thenReturn(message("assistant", "RUNNING", "生成中"));
        assertThatThrownBy(() -> service.create(OWNER, MESSAGE))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(QaMemoService.ERROR_ANSWER_NOT_COMPLETE));
    }

    @Test
    @DisplayName("消息不存在 / 会话不属于当前账号：一律 404，不泄漏是否存在")
    void messageOwnership() {
        when(messageMapper.selectById(MESSAGE)).thenReturn(null);
        assertThatThrownBy(() -> service.create(OWNER, MESSAGE))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(QaMemoService.ERROR_MESSAGE_NOT_FOUND));

        QaConversation foreign = new QaConversation();
        foreign.setId(CONV);
        foreign.setCreatedBy("clinician");
        when(conversationMapper.selectById(CONV)).thenReturn(foreign);
        when(messageMapper.selectById(MESSAGE)).thenReturn(message("assistant", "OK", "结论"));
        assertThatThrownBy(() -> service.create(OWNER, MESSAGE))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(QaMemoService.ERROR_MESSAGE_NOT_FOUND));
    }

    @Test
    @DisplayName("找不到关联问答运行：拒绝，而不是存一条没有患者归属的孤儿备忘")
    void missingRunIsRejected() {
        when(runMapper.selectOne(any())).thenReturn(null);
        when(messageMapper.selectById(MESSAGE)).thenReturn(message("assistant", "OK", "结论"));
        assertThatThrownBy(() -> service.create(OWNER, MESSAGE))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(QaMemoService.ERROR_SOURCE_MISSING));
    }

    @Test
    @DisplayName("患者维度列表：只返回自己的，limit 多取一条判定 hasMore 并给出游标")
    void listPaging() {
        for (int i = 1; i <= 3; i++) {
            QaMemo memo = new QaMemo();
            memo.setId("qmem_" + i);
            memo.setOwner(OWNER);
            memo.setPatientId(PATIENT);
            memo.setSourceMessageId("qmsg_" + i);
            memo.setQuestion("问题" + i);
            memo.setContent("答案" + i);
            memo.setContentHash("hash" + i);
            memo.setCreatedAt(OffsetDateTime.now().minusMinutes(i));
            store.put(memo.getId(), memo);
        }

        QaAuxiliaryContract.MemoPage page = service.list(OWNER, PATIENT, null, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isNotBlank();
    }

    @Test
    @DisplayName("患者越权列表 404；详情越权 404")
    void listAndDetailOwnership() {
        Patient foreign = new Patient();
        foreign.setId(PATIENT);
        foreign.setCreatedBy("clinician");
        when(patientMapper.selectById(PATIENT)).thenReturn(foreign);
        assertThatThrownBy(() -> service.list(OWNER, PATIENT, null, 20))
                .isInstanceOf(BizException.class);

        QaMemo memo = new QaMemo();
        memo.setId("qmem_x");
        memo.setOwner("clinician");
        memo.setPatientId(PATIENT);
        memo.setSourceMessageId(MESSAGE);
        memo.setContent("别人的答案");
        memo.setContentHash("h");
        memo.setCreatedAt(OffsetDateTime.now());
        store.put(memo.getId(), memo);
        assertThatThrownBy(() -> service.detail(OWNER, "qmem_x"))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(QaMemoService.ERROR_MEMO_NOT_FOUND));
    }

    @Test
    @DisplayName("详情还原引用快照为 JSON 数组，而不是把字符串塞给前端")
    void detailRestoresSnapshot() {
        when(messageMapper.selectById(MESSAGE)).thenReturn(message("assistant", "OK", "建议规律作息。"));
        QaAuxiliaryContract.MemoCreated created = service.create(OWNER, MESSAGE);

        QaAuxiliaryContract.MemoDetail detail = service.detail(OWNER, created.memoId());

        assertThat(detail.content()).isEqualTo("建议规律作息。");
        assertThat(detail.evidenceSnapshot()).isNotEmpty();
        assertThat(detail.evidenceSnapshot().get(0)).containsEntry("chunkId", "chunk_1");
    }
}
