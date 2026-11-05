package com.kangheng.healthos.service.qa.run;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import com.kangheng.healthos.model.qa.Citation;
import com.kangheng.healthos.mapper.classics.ClassicDocumentMapper;
import com.kangheng.healthos.model.classics.ClassicDocument;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.service.graph.GraphService;
import com.kangheng.healthos.service.outbox.OutboxService;
import com.kangheng.healthos.mapper.patient.PatientMetricMapper;
import com.kangheng.healthos.contract.qa.QaRunContract;
import com.kangheng.healthos.mapper.qa.QaAttemptMapper;
import com.kangheng.healthos.mapper.qa.QaConversationMapper;
import com.kangheng.healthos.mapper.qa.QaEventMapper;
import com.kangheng.healthos.mapper.qa.QaMessageMapper;
import com.kangheng.healthos.mapper.qa.QaRunMapper;
import com.kangheng.healthos.mapper.qa.QaRunNodeMapper;
import com.kangheng.healthos.model.qa.QaAttempt;
import com.kangheng.healthos.model.qa.QaConversation;
import com.kangheng.healthos.model.qa.QaEvent;
import com.kangheng.healthos.model.qa.QaMessage;
import com.kangheng.healthos.model.qa.QaRun;
import com.kangheng.healthos.model.qa.QaRunNode;
import com.kangheng.healthos.spi.qa.AnswerBranch;
import com.kangheng.healthos.spi.qa.BranchContext;
import com.kangheng.healthos.service.qa.BranchOrchestrator;
import com.kangheng.healthos.spi.qa.BranchResult;
import com.kangheng.healthos.spi.retrieval.KnowledgeBase;
import com.kangheng.healthos.service.tracing.TracingService;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.kangheng.healthos.mapper.tracing.TraceEventMapper;
import com.kangheng.healthos.mapper.tracing.TraceRunMapper;
import com.kangheng.healthos.mapper.tracing.TraceSpanMapper;
import com.kangheng.healthos.provider.tracing.DirectTraceWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 问答运行（run）的流程约束。
 *
 * <p>这一组用例守的是<b>长任务的正确性</b>：
 * <ul>
 *   <li>幂等：同一键重复提交不产生第二个运行；同键换问题明确报错；</li>
 *   <li>断点续跑：已成功的节点不会被重跑，只重跑失败的那个；</li>
 *   <li>重试边界：不能重试没失败的节点（那会破坏"答案依据可回溯"）；</li>
 *   <li>事件流：seq 连续递增，SSE 断点续传不会漏事件；</li>
 *   <li>owner 隔离：别人的运行一律 404。</li>
 * </ul>
 */
class QaRunServiceTest {

    private QaRunMapper runMapper;
    private QaRunNodeMapper nodeMapper;
    private QaEventMapper eventMapper;
    private QaAttemptMapper attemptMapper;
    private QaConversationMapper conversationMapper;
    private QaMessageMapper messageMapper;
    private ClassicDocumentMapper documentMapper;
    private KnowledgeBase knowledgeBase;
    private GraphService graphService;
    private OutboxService outboxService;
    private HealthOsProperties properties;
    private QaRunService service;
    private QaRunNodeExecutor executor;

    /** 内存态的运行与节点，模拟数据库。 */
    private final Map<String, QaRun> runs = new java.util.HashMap<>();
    private final Map<String, QaRunNode> nodes = new java.util.LinkedHashMap<>();
    private final List<QaEvent> events = new ArrayList<>();
    private final AtomicInteger seqCounter = new AtomicInteger();

    @BeforeAll
    static void initMeta() {
        initMeta(QaRun.class);
        initMeta(QaRunNode.class);
        initMeta(QaEvent.class);
        initMeta(QaAttempt.class);
        initMeta(QaMessage.class);
        initMeta(QaConversation.class);
    }

    private static void initMeta(Class<?> entity) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
    }

    @BeforeEach
    void setUp() {
        runs.clear();
        nodes.clear();
        events.clear();
        seqCounter.set(0);

        runMapper = mock(QaRunMapper.class);
        nodeMapper = mock(QaRunNodeMapper.class);
        eventMapper = mock(QaEventMapper.class);
        attemptMapper = mock(QaAttemptMapper.class);
        conversationMapper = mock(QaConversationMapper.class);
        messageMapper = mock(QaMessageMapper.class);
        documentMapper = mock(ClassicDocumentMapper.class);
        knowledgeBase = mock(KnowledgeBase.class);
        graphService = mock(GraphService.class);
        outboxService = mock(OutboxService.class);
        properties = new HealthOsProperties();

        when(runMapper.insert(any(QaRun.class))).thenAnswer(invocation -> {
            QaRun run = invocation.getArgument(0);
            runs.put(run.getId(), run);
            return 1;
        });
        when(runMapper.updateById(any(QaRun.class))).thenAnswer(invocation -> {
            QaRun update = invocation.getArgument(0);
            QaRun stored = runs.get(update.getId());
            if (stored != null) {
                // 模拟 updateById 只更新非 null 字段
                if (update.getStatus() != null) stored.setStatus(update.getStatus());
                if (update.getAnswer() != null) stored.setAnswer(update.getAnswer());
                if (update.getVersion() != null) stored.setVersion(update.getVersion());
                if (update.getErrorCode() != null) stored.setErrorCode(update.getErrorCode());
                if (update.getErrorMessage() != null) stored.setErrorMessage(update.getErrorMessage());
                if (update.getCompletedAt() != null) stored.setCompletedAt(update.getCompletedAt());
                if (update.getContextSnapshot() != null) stored.setContextSnapshot(update.getContextSnapshot());
                if (update.getStartedAt() != null) stored.setStartedAt(update.getStartedAt());
                if (update.getUpdatedAt() != null) stored.setUpdatedAt(update.getUpdatedAt());
            }
            return 1;
        });
        when(runMapper.update(isNull(), any())).thenAnswer(invocation -> {
            applyWrapper(invocation.getArgument(1), runs);
            return 1;
        });
        when(runMapper.selectById(anyString())).thenAnswer(invocation -> runs.get(invocation.getArgument(0)));
        // 幂等查重按「归属人 + 幂等键」定位。
        when(runMapper.selectOne(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<QaRun> wrapper = invocation.getArgument(0);
            // 必须先渲染一次 SQL，参数才会进 paramNameValuePairs
            wrapper.getSqlSegment();
            // paramNameValuePairs 是无序 Map，不能按插入顺序取值；
            // 必须按 SQL 里的占位符名字反查列。
            java.util.Map<String, Object> params = wrapper.getParamNameValuePairs();
            String sql = wrapper.getSqlSegment();
            String owner = paramFor(sql, params, "owner");
            String key = paramFor(sql, params, "idempotency_key");
            return runs.values().stream()
                    .filter(run -> run.getOwner().equals(owner) && run.getIdempotencyKey().equals(key))
                    .findFirst().orElse(null);
        });

        when(nodeMapper.insert(any(QaRunNode.class))).thenAnswer(invocation -> {
            QaRunNode node = invocation.getArgument(0);
            nodes.put(node.getId(), node);
            return 1;
        });
        // 生产代码用 update(null, wrapper) 显式置 null（updateById 会忽略 null 字段），
        // 所以 mock 必须走 wrapper 这条路，否则测试测不到"清空错误信息"这个行为。
        when(nodeMapper.update(isNull(), any())).thenAnswer(invocation -> {
            applyWrapper(invocation.getArgument(1), nodes);
            return 1;
        });
        when(nodeMapper.updateById(any(QaRunNode.class))).thenAnswer(invocation -> {
            QaRunNode update = invocation.getArgument(0);
            QaRunNode stored = nodes.get(update.getId());
            if (stored != null) {
                if (update.getStatus() != null) stored.setStatus(update.getStatus());
                if (update.getResult() != null) stored.setResult(update.getResult());
                if (update.getErrorCode() != null) stored.setErrorCode(update.getErrorCode());
                if (update.getErrorMessage() != null) stored.setErrorMessage(update.getErrorMessage());
                if (update.getAttemptCount() != null) stored.setAttemptCount(update.getAttemptCount());
                if (update.getStartedAt() != null) stored.setStartedAt(update.getStartedAt());
                if (update.getCompletedAt() != null) stored.setCompletedAt(update.getCompletedAt());
                if (update.getUpdatedAt() != null) stored.setUpdatedAt(update.getUpdatedAt());
            }
            return 1;
        });
        when(nodeMapper.selectList(any())).thenAnswer(invocation -> {
            List<QaRunNode> result = new ArrayList<>();
            for (QaRunNode node : nodes.values()) {
                result.add(node);
            }
            result.sort(java.util.Comparator.comparing(QaRunNode::getOrderIndex));
            return result;
        });

        when(eventMapper.nextSeq(anyString())).thenAnswer(invocation -> seqCounter.incrementAndGet());
        when(eventMapper.insert(any(QaEvent.class))).thenAnswer(invocation -> {
            events.add(invocation.getArgument(0));
            return 1;
        });
        when(eventMapper.listAfter(anyString(), anyInt(), anyInt())).thenAnswer(invocation -> {
            String runId = invocation.getArgument(0);
            int after = invocation.getArgument(1);
            int limit = invocation.getArgument(2);
            List<QaEvent> result = new ArrayList<>();
            for (QaEvent event : events) {
                if (event.getRunId().equals(runId) && event.getSeq() > after) {
                    result.add(event);
                }
            }
            result.sort(java.util.Comparator.comparing(QaEvent::getSeq));
            return result.size() > limit ? result.subList(0, limit) : result;
        });

        when(attemptMapper.insert(any(QaAttempt.class))).thenReturn(1);
        when(attemptMapper.updateById(any(QaAttempt.class))).thenReturn(1);
        when(attemptMapper.selectList(any())).thenReturn(List.of());
        when(conversationMapper.insert(any(QaConversation.class))).thenAnswer(invocation -> 1);
        when(messageMapper.insert(any(QaMessage.class))).thenAnswer(invocation -> 1);

        ClassicDocument document = new ClassicDocument();
        document.setId("doc_1");
        document.setTitle("四君子汤");
        document.setStatus("READY");
        when(documentMapper.selectById("doc_1")).thenReturn(document);
        when(knowledgeBase.retrieve(anyString(), anyString(), anyInt())).thenReturn(List.of(
                new KnowledgeBase.RetrievedChunk("c1", "四君子汤益气健脾", 3, 100, 108, 0.9)));
        when(knowledgeBase.name()).thenReturn("local");
        when(graphService.storeName()).thenReturn("jdbc");

        BranchOrchestrator orchestrator = new BranchOrchestrator(
                List.of(new StubBranch("ragflow", "古籍原文", 10, () -> BranchResult.succeeded(
                        "ragflow", "古籍原文", BranchResult.BASIS_RETRIEVED_EVIDENCE, "原文答案",
                        List.of(new Citation("c1", "四君子汤益气健脾", 3, 100, 108, 0.9)), 5))),
                directExecutor(), properties, tracing());

        executor = new QaRunNodeExecutor(orchestrator, nodeMapper, eventMapper, attemptMapper);
        service = new QaRunService(runMapper, nodeMapper, eventMapper, attemptMapper,
                conversationMapper, messageMapper, documentMapper, knowledgeBase, graphService,
                mock(PatientMetricMapper.class), executor, outboxService, properties, tracing(),
                mock(com.kangheng.healthos.service.qa.KnowledgeBindingService.class));
    }

    /**
     * 把 update wrapper 的 SET 子句应用到内存实体上。
     *
     * <p>解析 {@code getSqlSet()} 拿到「列名 = 占位符」，再用
     * {@code getParamNameValuePairs()} 把占位符换成真实值，最后按列名反射写字段。
     * 这样 mock 的行为与真实 SQL 一致——包括把字段置 null 的情况。
     */
    private static void applyWrapper(Object wrapper, Map<String, ?> store) {
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?> wrapper0 =
                (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?>) wrapper;
        String sqlSet = wrapper0.getSqlSet();
        String where = wrapper0.getSqlSegment();
        java.util.Map<String, Object> params = wrapper0.getParamNameValuePairs();

        String id = paramFor(where, params, "id");
        Object target = store.get(id);
        if (target == null || sqlSet == null) {
            return;
        }
        // SET 子句按逗号切分，但值本身可能是含逗号的 JSON，
        // 所以只能在「逗号后面紧跟 列名=」的位置切。
        for (String assignment : sqlSet.split(",(?=[a-z_]+=)")) {
            int eq = assignment.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String column = assignment.substring(0, eq).trim();
            Object value = paramValue(assignment, params, column);
            applyColumn(target, column, value);
        }
    }

    /** 按数据库列名写实体字段。列名与 Java 属性名的映射按驼峰规则处理。 */
    private static void applyColumn(Object target, String column, Object value) {
        String property = java.util.Arrays.stream(column.split("_"))
                .map(part -> part.isEmpty() ? part
                        : Character.toUpperCase(part.charAt(0)) + part.substring(1))
                .reduce("", String::concat);
        String setterName = "set" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
        for (java.lang.reflect.Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(setterName) || method.getParameterCount() != 1) {
                continue;
            }
            try {
                method.invoke(target, coerce(value, method.getParameterTypes()[0]));
                return;
            } catch (ReflectiveOperationException ignored) {
                // 类型对不上就跳过：测试只关心与本用例相关的字段
            }
        }
    }

    /** 把参数值转成字段需要的类型（wrapper 里的值都是原始类型）。 */
    private static Object coerce(Object value, Class<?> type) {
        if (value == null) {
            return null;
        }
        if (type == String.class) {
            return String.valueOf(value);
        }
        if (type == Integer.class) {
            return value instanceof Number number ? number.intValue() : Integer.valueOf(String.valueOf(value));
        }
        if (type == OffsetDateTime.class) {
            return value instanceof OffsetDateTime time ? time
                    : OffsetDateTime.parse(String.valueOf(value));
        }
        return value;
    }

    /**
     * 从 SQL 片段的 #{...} 占位符反查参数值。
     *
     * <p>返回 {@link Object} 而不是 String：null 必须原样传回去，
     * 转成字符串会变成 "null"，那就不是"清空字段"而是"写入字符串 null"了。
     */
    private static Object paramValue(String sql, java.util.Map<String, Object> params, String column) {
        if (sql == null) {
            return null;
        }
        // sqlSet 用逗号分隔且不带空格（"version=#{...}"），where 子句带空格（"id = #{...}"），
        // 所以这里两种写法都要能匹配上。
        String tight = column + "=#{ew.paramNameValuePairs.";
        String loose = column + " = #{ew.paramNameValuePairs.";
        int start = sql.indexOf(tight);
        int prefix;
        if (start >= 0) {
            prefix = tight.length();
        } else {
            start = sql.indexOf(loose);
            if (start < 0) {
                return null;
            }
            prefix = loose.length();
        }
        int from = start + prefix;
        int end = sql.indexOf('}', from);
        if (end < 0) {
            return null;
        }
        return params.get(sql.substring(from, end));
    }

    /** 从 SQL 片段的 #{...} 占位符反查参数值（字符串形态）。 */
    private static String paramFor(String sql, java.util.Map<String, Object> params, String column) {
        Object value = paramValue(sql, params, column);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 同步执行器：单测里分支不该另起线程，否则断言会跑在执行完成之前。
     *
     * <p>Runnable 与 Callable 两个重载都要 stub——分支编排走的是 Callable，
     * 漏掉哪一个都会让分支"根本没执行"，测试却因为异常被吞掉而假绿。
     */
    private java.util.concurrent.ExecutorService directExecutor() {
        java.util.concurrent.ExecutorService service = mock(java.util.concurrent.ExecutorService.class);
        when(service.submit(any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        when(service.submit(any(java.util.concurrent.Callable.class))).thenAnswer(invocation -> {
            java.util.concurrent.Callable<?> callable = invocation.getArgument(0);
            try {
                return java.util.concurrent.CompletableFuture.completedFuture(callable.call());
            } catch (Exception ex) {
                return java.util.concurrent.CompletableFuture.failedFuture(ex);
            }
        });
        return service;
    }

    private TracingService tracing() {
        HealthOsProperties tracingProps = new HealthOsProperties();
        tracingProps.getTracing().setEnabled(false);
        return new TracingService(mock(com.kangheng.healthos.mapper.tracing.TraceRunMapper.class),
                mock(com.kangheng.healthos.mapper.tracing.TraceSpanMapper.class),
                mock(com.kangheng.healthos.mapper.tracing.TraceEventMapper.class),
                new com.kangheng.healthos.provider.tracing.DirectTraceWriter(), tracingProps);
    }

    // ---------------------------------------------------------------- 提交

    @Test
    @DisplayName("提交后落库并派发：运行、节点、事件、outbox 四样都要有")
    void submitPersistsAndDispatches() {
        QaRunContract.AcceptedResponse accepted = submit("doc_1", "四君子汤主治什么", "k1");

        assertThat(accepted.status()).isEqualTo(QaRunService.STATUS_QUEUED);
        assertThat(accepted.runId()).isNotBlank();
        assertThat(runs).hasSize(1);
        // retrieval + 一个分支 + summary
        assertThat(nodes).hasSize(3);
        assertThat(events).isNotEmpty();
        verify(outboxService).append(anyString(), anyString(), eq(QaRunService.EVENT_EXECUTE), any());
    }

    @Test
    @DisplayName("同一幂等键重复提交：返回同一个运行，不新建")
    void sameIdempotencyKeyReplays() {
        QaRunContract.AcceptedResponse first = submit("doc_1", "四君子汤主治什么", "k1");
        QaRunContract.AcceptedResponse second = submit("doc_1", "四君子汤主治什么", "k1");

        assertThat(second.runId()).isEqualTo(first.runId());
        assertThat(runs).hasSize(1);
    }

    @Test
    @DisplayName("同一幂等键换问题：明确冲突，不静默复用")
    void sameIdempotencyKeyDifferentQuestionConflicts() {
        submit("doc_1", "四君子汤主治什么", "k1");

        assertThatThrownBy(() -> submit("doc_1", "四君子汤怎么用", "k1"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(QaRunService.ERROR_IDEMPOTENCY_CONFLICT);
        assertThat(runs).hasSize(1);
    }

    @Test
    @DisplayName("文档没解析完就提问：拒绝，不受理")
    void submitRejectsUnreadyDocument() {
        ClassicDocument document = new ClassicDocument();
        document.setId("doc_2");
        document.setStatus("PROCESSING");
        when(documentMapper.selectById("doc_2")).thenReturn(document);

        assertThatThrownBy(() -> service.submit("doctor",
                new QaRunContract.SubmitRequest("doc_2", null, "问题", "parallel", null, "k2", null)))
                .isInstanceOf(BizException.class);
        assertThat(runs).isEmpty();
    }

    // ---------------------------------------------------------------- 断点续跑

    @Test
    @DisplayName("续跑：已成功的节点被跳过，只有失败节点真的执行")
    void resumeSkipsSucceededNodes() {
        QaRunContract.AcceptedResponse accepted = submit("doc_1", "问题", "k1");
        String runId = accepted.runId();

        // 手工把分支节点置为已成功，并留下结果
        QaRunNode branchNode = nodes.values().stream()
                .filter(node -> "ragflow".equals(node.getNodeKey())).findFirst().orElseThrow();
        branchNode.setStatus(QaRunService.NODE_SUCCEEDED);
        branchNode.setResult(Json.write(Map.of(
                "status", "SUCCEEDED", "basis", "retrieved_evidence", "answer", "上次算出的答案",
                "citations", List.of(), "elapsedMs", 5)));

        service.execute(runId);

        // 分支节点被跳过（attemptCount 没有增加），汇总节点读的是上次的结果
        QaRunNode summary = nodes.values().stream()
                .filter(node -> QaNodeSpec.SUMMARY.equals(node.getNodeKey())).findFirst().orElseThrow();
        assertThat(summary.getStatus()).isEqualTo(QaRunService.NODE_SUCCEEDED);
        assertThat(summary.getResult()).contains("上次算出的答案");
        assertThat(branchNode.getAttemptCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("续跑：失败节点会重跑，成功节点不动")
    void resumeRetriesOnlyFailedNode() {
        QaRunContract.AcceptedResponse accepted = submit("doc_1", "问题", "k1");
        String runId = accepted.runId();

        QaRunNode branchNode = nodes.values().stream()
                .filter(node -> "ragflow".equals(node.getNodeKey())).findFirst().orElseThrow();
        branchNode.setStatus(QaRunService.NODE_FAILED);
        branchNode.setErrorCode("QA_MODEL_FAILED");
        branchNode.setAttemptCount(1);

        service.execute(runId);

        assertThat(branchNode.getStatus()).isEqualTo(QaRunService.NODE_SUCCEEDED);
        assertThat(branchNode.getAttemptCount()).isEqualTo(2);
        assertThat(runs.get(runId).getStatus()).isEqualTo(QaRunService.STATUS_SUCCEEDED);
    }

    @Test
    @DisplayName("执行成功后助手消息落库，答案来自汇总节点")
    void successfulRunPersistsAssistantMessage() {
        QaRunContract.AcceptedResponse accepted = submit("doc_1", "问题", "k1");
        service.execute(accepted.runId());

        org.mockito.ArgumentCaptor<QaMessage> captor = org.mockito.ArgumentCaptor.forClass(QaMessage.class);
        verify(messageMapper, times(2)).insert(captor.capture());
        List<QaMessage> messages = captor.getAllValues();
        assertThat(messages.get(0).getRole()).isEqualTo("user");
        assertThat(messages.get(1).getRole()).isEqualTo("assistant");
        assertThat(messages.get(1).getContent()).contains("原文答案");
        // 引用必须是 JSON 字符串，不能是 Java 的 toString
        assertThat(messages.get(1).getCitations()).startsWith("[");
    }

    // ---------------------------------------------------------------- 重试

    @Test
    @DisplayName("重试：只允许重跑失败节点")
    void retryRejectsSucceededNode() {
        QaRunContract.AcceptedResponse accepted = submit("doc_1", "问题", "k1");
        String runId = accepted.runId();
        QaRunNode branchNode = nodes.values().stream()
                .filter(node -> "ragflow".equals(node.getNodeKey())).findFirst().orElseThrow();
        branchNode.setStatus(QaRunService.NODE_FAILED);

        assertThatThrownBy(() -> service.retry("doctor", runId,
                new QaRunContract.RetryRequest(List.of(QaNodeSpec.RETRIEVAL), 1, null)))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(QaRunService.ERROR_NODE_INVALID);
    }

    @Test
    @DisplayName("重试：失败节点被重置为待跑，并重新派发")
    void retryResetsFailedNode() {
        QaRunContract.AcceptedResponse accepted = submit("doc_1", "问题", "k1");
        String runId = accepted.runId();
        QaRunNode branchNode = nodes.values().stream()
                .filter(node -> "ragflow".equals(node.getNodeKey())).findFirst().orElseThrow();
        branchNode.setStatus(QaRunService.NODE_FAILED);

        QaRunContract.AcceptedResponse retried = service.retry("doctor", runId,
                new QaRunContract.RetryRequest(List.of("ragflow"), 1, null));

        assertThat(retried.status()).isEqualTo(QaRunService.STATUS_QUEUED);
        assertThat(branchNode.getStatus()).isEqualTo(QaRunService.NODE_PENDING);
        verify(outboxService, times(2)).append(anyString(), anyString(), eq(QaRunService.EVENT_EXECUTE), any());
    }

    @Test
    @DisplayName("重试：汇总节点必须一起重跑，否则最终答案还是上一轮的")
    void retryAlsoRerunsSummary() {
        QaRunContract.AcceptedResponse accepted = submit("doc_1", "问题", "k1");
        String runId = accepted.runId();
        QaRunNode branchNode = nodes.values().stream()
                .filter(node -> "ragflow".equals(node.getNodeKey())).findFirst().orElseThrow();
        branchNode.setStatus(QaRunService.NODE_FAILED);
        // 汇总节点此前已经成功过——正是这个场景最容易漏掉重算
        QaRunNode summary = nodes.values().stream()
                .filter(node -> QaNodeSpec.SUMMARY.equals(node.getNodeKey())).findFirst().orElseThrow();
        summary.setStatus(QaRunService.NODE_SUCCEEDED);
        summary.setResult(Json.write(Map.of("status", "OK", "answer", "上一轮的答案")));

        service.retry("doctor", runId, new QaRunContract.RetryRequest(List.of("ragflow"), 1, null));

        assertThat(summary.getStatus()).isEqualTo(QaRunService.NODE_PENDING);
    }

    @Test
    @DisplayName("重试：版本不匹配时明确失败，不覆盖别人的操作")
    void retryRejectsStaleVersion() {
        QaRunContract.AcceptedResponse accepted = submit("doc_1", "问题", "k1");
        QaRunNode branchNode = nodes.values().stream()
                .filter(node -> "ragflow".equals(node.getNodeKey())).findFirst().orElseThrow();
        branchNode.setStatus(QaRunService.NODE_FAILED);

        assertThatThrownBy(() -> service.retry("doctor", accepted.runId(),
                new QaRunContract.RetryRequest(null, 99, null)))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(QaRunService.ERROR_VERSION_CONFLICT);
    }

    // ---------------------------------------------------------------- 事件流

    @Test
    @DisplayName("事件 seq 连续递增，按 afterSeq 能补齐不重不漏")
    void eventStreamSupportsResume() {
        QaRunContract.AcceptedResponse accepted = submit("doc_1", "问题", "k1");
        service.execute(accepted.runId());

        QaRunContract.EventPage first = service.events("doctor", accepted.runId(), 0, 3);
        assertThat(first.items()).hasSize(3);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.items().get(0).seq()).isEqualTo(1);

        QaRunContract.EventPage second = service.events("doctor", accepted.runId(), first.nextSeq(), 100);
        assertThat(second.items()).isNotEmpty();
        // 补齐的部分不与第一段重叠
        assertThat(second.items().get(0).seq()).isGreaterThan(first.nextSeq());
    }

    // ---------------------------------------------------------------- owner 隔离

    @Test
    @DisplayName("越权查运行：404，不泄露运行是否存在")
    void runIsScopedToOwner() {
        QaRunContract.AcceptedResponse accepted = submit("doc_1", "问题", "k1");

        assertThatThrownBy(() -> service.detail("mallory", accepted.runId()))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getStatus().value())
                .isEqualTo(404);
        assertThatThrownBy(() -> service.events("mallory", accepted.runId(), 0, 10))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getStatus().value())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("越权取消：404，且不影响原运行状态")
    void cancelIsScopedToOwner() {
        QaRunContract.AcceptedResponse accepted = submit("doc_1", "问题", "k1");

        assertThatThrownBy(() -> service.cancel("mallory", accepted.runId(), 1))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getStatus().value())
                .isEqualTo(404);
        assertThat(runs.get(accepted.runId()).getStatus()).isEqualTo(QaRunService.STATUS_QUEUED);
    }

    // ---------------------------------------------------------------- 取消

    @Test
    @DisplayName("取消：运行与未完成节点都置为取消")
    void cancelMarksRunAndNodes() {
        QaRunContract.AcceptedResponse accepted = submit("doc_1", "问题", "k1");
        service.cancel("doctor", accepted.runId(), 1);

        assertThat(runs.get(accepted.runId()).getStatus()).isEqualTo(QaRunService.STATUS_CANCELLED);
        assertThat(nodes.values()).allMatch(node ->
                QaRunService.NODE_CANCELLED.equals(node.getStatus())
                        || QaRunService.NODE_SUCCEEDED.equals(node.getStatus()));
    }

    // ---------------------------------------------------------------- 辅助

    private QaRunContract.AcceptedResponse submit(String documentId, String question, String key) {
        return service.submit("doctor", new QaRunContract.SubmitRequest(
                documentId, null, question, "parallel", null, key, null));
    }

    private interface Action {
        BranchResult run();
    }

    private record StubBranch(String key, String label, int order, Action action)
            implements AnswerBranch {

        @Override
        public String key() {
            return key;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public BranchResult answer(BranchContext context) {
            return action.run();
        }
    }
}
