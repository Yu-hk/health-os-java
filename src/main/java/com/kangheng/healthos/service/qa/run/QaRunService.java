package com.kangheng.healthos.service.qa.run;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import com.kangheng.healthos.mapper.classics.ClassicDocumentMapper;
import com.kangheng.healthos.model.classics.ClassicDocument;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.model.graph.GraphView;
import com.kangheng.healthos.service.graph.GraphService;
import com.kangheng.healthos.service.outbox.OutboxService;
import com.kangheng.healthos.mapper.patient.PatientMetricMapper;
import com.kangheng.healthos.model.patient.PatientMetric;
import com.kangheng.healthos.contract.qa.QaContract;
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
import com.kangheng.healthos.service.qa.BranchAggregator;
import com.kangheng.healthos.spi.qa.BranchContext;
import com.kangheng.healthos.spi.qa.BranchResult;
import com.kangheng.healthos.spi.retrieval.KnowledgeBase;
import com.kangheng.healthos.service.tracing.TracingService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 问答运行（run）的生命周期管理。
 *
 * <p>解决的问题：原来 {@code /qa/ask} 是一次同步调用，模型超时、进程重启、客户端断线
 * 都会让这次问答前功尽弃，而且没有任何"从断点继续"的手段。这里把一次问答建模成
 * <b>运行（run）</b>，对齐原项目 {@code qa_runs.py} + {@code qa_worker.py}：
 *
 * <ol>
 *   <li><b>提交即受理</b>：{@code submit} 只落库并返回 runId，模型调用在 worker 侧发生。
 *       客户端拿到 202 后可以断开，也可以挂 SSE 断点续传；</li>
 *   <li><b>节点级断点续跑</b>：运行拆成 retrieval / 各分支 / summary 节点，
 *       每个节点的状态单独落库。续跑时已 SUCCEEDED 的节点直接跳过，
 *       只重跑失败或没跑的——"检索成功但模型超时"不必再打一次知识库；</li>
 *   <li><b>冻结上下文</b>：提交那一刻的检索命中、图谱、指标冻结进 {@code contextSnapshot}。
 *       续跑用的是冻结值而不是当前值，否则续跑出来的答案和首次不是一回事；</li>
 *   <li><b>事件流</b>：每次状态变化写一条 {@code qa_event}，seq 单调递增。
 *       SSE 按 seq 增量推送，客户端断线后带 {@code afterSeq} 重连即可补齐；</li>
 *   <li><b>幂等</b>：同一会话 + 同一幂等键 → 同一个 run；键相同但问题不同 → 409，
 *       不静默复用。</li>
 * </ol>
 *
 * <p><b>owner 隔离</b>：所有查询按归属人过滤，越权返回 404（不返回 403——
 * 403 等于承认"这个 ID 确实存在"，那本身就是信息泄露）。
 */
@Service
public class QaRunService {

    private static final Logger log = LoggerFactory.getLogger(QaRunService.class);

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_INSUFFICIENT = "INSUFFICIENT_EVIDENCE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final String NODE_PENDING = "PENDING";
    public static final String NODE_RUNNING = "RUNNING";
    public static final String NODE_SUCCEEDED = "SUCCEEDED";
    public static final String NODE_FAILED = "FAILED";
    public static final String NODE_SKIPPED = "SKIPPED";
    public static final String NODE_CANCELLED = "CANCELLED";

    public static final String ERROR_IDEMPOTENCY_CONFLICT = "QA_IDEMPOTENCY_CONFLICT";
    public static final String ERROR_VERSION_CONFLICT = "QA_VERSION_CONFLICT";
    public static final String ERROR_NODE_INVALID = "QA_RETRY_NODE_INVALID";
    public static final String ERROR_STATE = "QA_RUN_STATE_INVALID";
    public static final String ERROR_NOT_FOUND = "QA_RUN_NOT_FOUND";

    /** 派发问答运行的 outbox 事件类型。 */
    public static final String EVENT_EXECUTE = "qa.run.execute";

    /** 终态：进入这些状态后不再接受重试（取消除外，取消本身也是终态）。 */
    private static final List<String> TERMINAL = List.of(
            STATUS_SUCCEEDED, STATUS_INSUFFICIENT, STATUS_CANCELLED);

    private final QaRunMapper runMapper;
    private final QaRunNodeMapper nodeMapper;
    private final QaEventMapper eventMapper;
    private final QaAttemptMapper attemptMapper;
    private final QaConversationMapper conversationMapper;
    private final QaMessageMapper messageMapper;
    private final ClassicDocumentMapper documentMapper;
    private final KnowledgeBase knowledgeBase;
    private final GraphService graphService;
    private final PatientMetricMapper metricMapper;
    private final QaRunNodeExecutor nodeExecutor;
    private final OutboxService outboxService;
    private final HealthOsProperties properties;
    private final TracingService tracing;
    private final com.kangheng.healthos.service.qa.KnowledgeBindingService bindingService;

    public QaRunService(QaRunMapper runMapper,
                        QaRunNodeMapper nodeMapper,
                        QaEventMapper eventMapper,
                        QaAttemptMapper attemptMapper,
                        QaConversationMapper conversationMapper,
                        QaMessageMapper messageMapper,
                        ClassicDocumentMapper documentMapper,
                        KnowledgeBase knowledgeBase,
                        GraphService graphService,
                        PatientMetricMapper metricMapper,
                        QaRunNodeExecutor nodeExecutor,
                        OutboxService outboxService,
                        HealthOsProperties properties,
                        TracingService tracing,
                        com.kangheng.healthos.service.qa.KnowledgeBindingService bindingService) {
        this.runMapper = runMapper;
        this.nodeMapper = nodeMapper;
        this.eventMapper = eventMapper;
        this.attemptMapper = attemptMapper;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.documentMapper = documentMapper;
        this.knowledgeBase = knowledgeBase;
        this.graphService = graphService;
        this.metricMapper = metricMapper;
        this.nodeExecutor = nodeExecutor;
        this.outboxService = outboxService;
        this.properties = properties;
        this.tracing = tracing;
        this.bindingService = bindingService;
    }

    // ================================================================ 提交

    /**
     * 提交一次问答。
     *
     * <p>幂等语义：同一会话 + 同一 {@code idempotencyKey} → 返回同一个 run；
     * 键相同但输入不同 → 409 {@code QA_IDEMPOTENCY_CONFLICT}。
     */
    @Transactional
    public QaRunContract.AcceptedResponse submit(String username, QaRunContract.SubmitRequest request) {
        if (request == null || !StringUtils.hasText(request.documentId())) {
            throw BizException.badRequest("documentId 不能为空");
        }
        String question = request.question() == null ? "" : request.question().strip();
        if (question.isEmpty()) {
            throw BizException.badRequest("question 不能为空");
        }
        String owner = username == null ? "unknown" : username;

        ClassicDocument document = documentMapper.selectById(request.documentId());
        if (document == null) {
            throw BizException.notFound("典籍文档");
        }
        if (!"READY".equals(document.getStatus())) {
            throw BizException.conflict("文档尚未解析完成，当前状态为 " + document.getStatus());
        }

        String mode = resolveMode(request.mode());
        // 幂等键：显式给了就按它去重；没给则每次都是新的一次提交。
        // 不能拿"问题内容"当默认键——用户重复问同一个问题是合法的再次提问，
        // 静默返回上一次的结果会让人以为系统没听见。
        boolean explicitKey = StringUtils.hasText(request.idempotencyKey());
        String idempotencyKey = explicitKey
                ? request.idempotencyKey().strip()
                : Ids.newId("qkey");
        String inputHash = Ids.sha256Hex(mode + "|" + document.getId() + "|" + question + "|"
                + (request.patientId() == null ? "" : request.patientId()));

        // 幂等查重：同一归属人下同键只允许一次提交。
        // 放在建会话之前查，否则第二次提交会先建出一个空会话，垃圾数据留在库里。
        QaRun existing = explicitKey ? findByKey(owner, idempotencyKey) : null;
        if (existing != null) {
            if (!existing.getInputHash().equals(inputHash)) {
                throw new BizException(ERROR_IDEMPOTENCY_CONFLICT,
                        "同一提交标识不能用于不同问题或上下文", org.springframework.http.HttpStatus.CONFLICT);
            }
            return accepted(existing);
        }

        QaConversation conversation = resolveConversation(owner, document, request, question);

        OffsetDateTime now = OffsetDateTime.now();
        QaRun run = new QaRun();
        run.setId(Ids.newId("qrun"));
        run.setConversationId(conversation.getId());
        run.setDocumentId(document.getId());
        run.setOwner(owner);
        run.setIdempotencyKey(idempotencyKey);
        run.setInputHash(inputHash);
        run.setQuestion(question);
        run.setMode(mode);
        run.setPatientId(request.patientId());
        run.setStatus(STATUS_QUEUED);
        run.setVersion(1);
        run.setEventSeq(0);
        run.setConfigSnapshot(Json.write(freezeConfig(mode)));
        run.setDeadlineAt(now.plus(properties.getQa().getRunTimeout()));
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        runMapper.insert(run);

        // 冻结上下文：检索一次、三路共用，续跑时用这份快照
        QaContextSnapshot context = freezeContext(owner, document, question, request.patientId());
        run.setContextSnapshot(Json.write(context));
        runMapper.updateById(run);

        // 建节点：retrieval → 各分支 → summary
        createNodes(run, document.getTitle());

        // 用户消息立刻落库：即使模型还没回话，历史里也看得到"问过什么"
        saveMessage(conversation.getId(), "user", question, null, null, "OK");

        emit(run.getId(), "run.accepted", Map.of("mode", mode, "documentId", document.getId(),
                "questionChars", question.length()));

        // 派发到 outbox：模型调用在 worker 侧发生，接口立即返回。
        // 事件与 run 同事务写入——不会出现"运行已受理但永远没人执行"的孤儿。
        outboxService.append("qa_run", run.getId(), EVENT_EXECUTE, Map.of("runId", run.getId()));

        QaRunContract.AcceptedResponse accepted = accepted(run);
        log.info("问答已受理 runId={} conversationId={} owner={} mode={}",
                run.getId(), conversation.getId(), owner, mode);
        return accepted;
    }

    // ================================================================ 执行

    /**
     * 执行一次运行（worker 侧调用）。
     *
     * <p><b>断点续跑的核心就在这里</b>：逐节点判断状态，已 SUCCEEDED 的直接跳过并复用
     * 上次的结果，只有 PENDING / FAILED 的才真的跑一遍。
     */
    public void execute(String runId) {
        QaRun run = runMapper.selectById(runId);
        if (run == null) {
            throw BizException.notFound("问答运行");
        }
        if (TERMINAL.contains(run.getStatus())) {
            return;
        }
        if (run.getStatus() != null && run.getStatus().equals(STATUS_RUNNING)) {
            // 上一次执行中断在这里（进程重启），允许接管续跑
            log.info("接管未完成的问答运行 runId={}", runId);
        }

        markRunning(run);
        emit(runId, "run.started", Map.of("mode", run.getMode()));

        BranchContext branchContext = toBranchContext(run);
        List<QaRunNode> nodes = listNodes(runId);
        boolean failed = false;
        for (QaRunNode node : nodes) {
            if (NODE_SUCCEEDED.equals(node.getStatus())) {
                // 断点续跑：已成功的节点直接跳过
                log.info("跳过已完成的节点 runId={} node={}", runId, node.getNodeKey());
                emit(runId, "node.skipped", Map.of("nodeKey", node.getNodeKey()));
                continue;
            }
            if (NODE_CANCELLED.equals(node.getStatus())) {
                continue;
            }
            QaRunNode outcome = nodeExecutor.execute(run, node, branchContext);
            if (NODE_FAILED.equals(outcome.getStatus())) {
                failed = true;
                break;
            }
        }

        if (failed) {
            finishFailed(run);
            return;
        }
        finishSucceeded(run);
    }

    // ================================================================ 查询

    public QaRunContract.RunDetail detail(String username, String runId) {
        QaRun run = requireOwned(runId, username);
        List<QaRunContract.NodeView> nodes = listNodes(runId).stream()
                .map(node -> new QaRunContract.NodeView(node.getNodeKey(), node.getLabel(),
                        node.getStatus(), node.getAttemptCount(), node.getAutoAttemptLimit(),
                        node.getErrorCode(), node.getErrorMessage(),
                        node.getStartedAt(), node.getCompletedAt()))
                .toList();
        List<QaRunContract.AttemptView> attempts = attemptMapper.selectList(
                        new LambdaQueryWrapper<QaAttempt>()
                                .eq(QaAttempt::getRunId, runId)
                                .orderByAsc(QaAttempt::getStartedAt)).stream()
                .map(attempt -> new QaRunContract.AttemptView(attempt.getNodeKey(),
                        attempt.getAttemptNo(), attempt.getStatus(), attempt.getErrorCode(),
                        attempt.getErrorMessage(), attempt.getStartedAt(), attempt.getCompletedAt()))
                .toList();
        return new QaRunContract.RunDetail(run.getId(), run.getConversationId(), run.getDocumentId(),
                run.getQuestion(), run.getMode(), run.getStatus(), run.getVersion(), run.getAnswer(),
                run.getErrorCode(), run.getErrorMessage(), run.getEventSeq(), nodes, attempts,
                run.getCreatedAt(), run.getStartedAt(), run.getCompletedAt());
    }

    /**
     * 增量拉事件。{@code afterSeq} 之后的事件，按 seq 升序。
     *
     * <p>这是 SSE 的数据源：SSE 端点内部就是循环调它。
     */
    public QaRunContract.EventPage events(String username, String runId, int afterSeq, int limit) {
        QaRun run = requireOwned(runId, username);
        int capped = Math.min(Math.max(limit, 1), 500);
        List<QaEvent> rows = eventMapper.listAfter(runId, afterSeq, capped + 1);
        boolean hasMore = rows.size() > capped;
        List<QaEvent> page = hasMore ? rows.subList(0, capped) : rows;
        List<QaRunContract.EventView> items = page.stream()
                .map(event -> new QaRunContract.EventView(event.getSeq(), event.getType(),
                        Json.readMap(event.getPayload()), event.getCreatedAt()))
                .toList();
        int nextSeq = items.isEmpty() ? afterSeq : items.get(items.size() - 1).seq();
        return new QaRunContract.EventPage(runId, items, nextSeq, hasMore, run.getStatus());
    }

    // ================================================================ 重试 / 取消

    /**
     * 重试失败节点。
     *
     * <p>{@code nodeKeys} 为空表示"把所有 FAILED 节点重跑一遍"。
     * 只能重试 FAILED 节点：已成功的节点重跑既浪费，也可能因为外部状态变化
     * 产出与上次不同的依据，破坏"这次运行的答案可回溯"这个前提。
     */
    @Transactional
    public QaRunContract.AcceptedResponse retry(String username, String runId,
                                                 QaRunContract.RetryRequest request) {
        QaRun run = requireOwned(runId, username);
        if (TERMINAL.contains(run.getStatus())) {
            throw new BizException(ERROR_STATE, "该问答已结束，不能重试", org.springframework.http.HttpStatus.CONFLICT);
        }
        if (request.expectedVersion() != null && !request.expectedVersion().equals(run.getVersion())) {
            throw new BizException(ERROR_VERSION_CONFLICT, "问答版本已变化，请刷新后重试",
                    org.springframework.http.HttpStatus.CONFLICT);
        }
        List<QaRunNode> nodes = listNodes(runId);
        List<String> chosen = request.nodeKeys() == null || request.nodeKeys().isEmpty()
                ? nodes.stream().filter(node -> NODE_FAILED.equals(node.getStatus()))
                        .map(QaRunNode::getNodeKey).toList()
                : request.nodeKeys().stream().filter(StringUtils::hasText).distinct().sorted().toList();
        if (chosen.isEmpty()) {
            throw BizException.badRequest("没有可重试的失败节点");
        }
        for (String key : chosen) {
            QaRunNode node = nodes.stream().filter(item -> key.equals(item.getNodeKey())).findFirst()
                    .orElseThrow(() -> new BizException(ERROR_NODE_INVALID, "节点不存在: " + key,
                            org.springframework.http.HttpStatus.BAD_REQUEST));
            if (!NODE_FAILED.equals(node.getStatus())) {
                throw new BizException(ERROR_NODE_INVALID, "仅可重试失败的节点: " + key,
                        org.springframework.http.HttpStatus.BAD_REQUEST);
            }
            // 重置为待跑；attemptCount 保留，用来累计尝试次数。
            // 错误信息必须显式置 null——updateById 会忽略 null 字段，
            // 留着上次的错误会让重试后的节点看起来仍然失败。
            nodeMapper.update(null, new LambdaUpdateWrapper<QaRunNode>()
                    .eq(QaRunNode::getId, node.getId())
                    .set(QaRunNode::getStatus, NODE_PENDING)
                    .set(QaRunNode::getErrorCode, null)
                    .set(QaRunNode::getErrorMessage, null)
                    .set(QaRunNode::getResult, null)
                    .set(QaRunNode::getCompletedAt, null)
                    .set(QaRunNode::getUpdatedAt, OffsetDateTime.now()));
        }

        // 汇总节点必须一起重跑：它读的是各分支节点的落库结果，
        // 分支换了新结果而汇总不重算，最终答案就还是上一轮的。
        List<String> toRerun = new ArrayList<>(chosen);
        nodes.stream()
                .filter(node -> QaNodeSpec.SUMMARY.equals(node.getNodeKey()))
                .filter(node -> !toRerun.contains(node.getNodeKey()))
                .forEach(node -> {
                    nodeMapper.update(null, new LambdaUpdateWrapper<QaRunNode>()
                            .eq(QaRunNode::getId, node.getId())
                            .set(QaRunNode::getStatus, NODE_PENDING)
                            .set(QaRunNode::getResult, null)
                            .set(QaRunNode::getCompletedAt, null)
                            .set(QaRunNode::getUpdatedAt, OffsetDateTime.now()));
                    toRerun.add(node.getNodeKey());
                });

        runMapper.update(null, new LambdaUpdateWrapper<QaRun>()
                .eq(QaRun::getId, runId)
                .set(QaRun::getStatus, STATUS_QUEUED)
                .set(QaRun::getVersion, run.getVersion() + 1)
                .set(QaRun::getErrorCode, null)
                .set(QaRun::getErrorMessage, null)
                .set(QaRun::getCompletedAt, null)
                .set(QaRun::getUpdatedAt, OffsetDateTime.now()));

        emit(runId, "run.retry", Map.of("nodes", chosen, "rerun", toRerun));
        // 重新派发：已成功的节点会被跳过，只重跑失败的那几个
        outboxService.append("qa_run", runId, EVENT_EXECUTE, Map.of("runId", runId));
        log.info("问答重试已受理 runId={} nodes={} rerun={}", runId, chosen, toRerun);
        // 响应要用更新后的状态：把变更回填到内存对象再组装，
        // 否则调用方拿到的还是重试前的 FAILED 与旧版本号。
        run.setStatus(STATUS_QUEUED);
        run.setVersion(run.getVersion() + 1);
        run.setErrorCode(null);
        run.setErrorMessage(null);
        run.setCompletedAt(null);
        return accepted(run);
    }

    /** 取消运行。已终态的运行直接返回当前状态（幂等）。 */
    @Transactional
    public QaRunContract.AcceptedResponse cancel(String username, String runId, Integer expectedVersion) {
        QaRun run = requireOwned(runId, username);
        if (TERMINAL.contains(run.getStatus())) {
            return accepted(run);
        }
        if (expectedVersion != null && expectedVersion > run.getVersion()) {
            throw new BizException(ERROR_VERSION_CONFLICT, "问答版本不匹配，请刷新",
                    org.springframework.http.HttpStatus.CONFLICT);
        }
        runMapper.update(null, new LambdaUpdateWrapper<QaRun>()
                .eq(QaRun::getId, runId)
                .set(QaRun::getStatus, STATUS_CANCELLED)
                .set(QaRun::getVersion, run.getVersion() + 1)
                .set(QaRun::getCompletedAt, OffsetDateTime.now())
                .set(QaRun::getUpdatedAt, OffsetDateTime.now()));

        for (QaRunNode node : listNodes(runId)) {
            if (NODE_SUCCEEDED.equals(node.getStatus()) || NODE_FAILED.equals(node.getStatus())) {
                continue;
            }
            nodeMapper.update(null, new LambdaUpdateWrapper<QaRunNode>()
                    .eq(QaRunNode::getId, node.getId())
                    .set(QaRunNode::getStatus, NODE_CANCELLED)
                    .set(QaRunNode::getUpdatedAt, OffsetDateTime.now()));
        }
        emit(runId, "run.cancelled", Map.of("version", run.getVersion() + 1));
        run.setStatus(STATUS_CANCELLED);
        run.setVersion(run.getVersion() + 1);
        run.setCompletedAt(OffsetDateTime.now());
        return accepted(run);
    }

    // ================================================================ 内部

    /** 写一条事件。seq 由数据库原子分配，避免并发写重号。 */
    public void emit(String runId, String type, Map<String, Object> payload) {
        Integer seq = eventMapper.nextSeq(runId);
        if (seq == null) {
            log.warn("问答运行不存在，无法写事件 runId={} type={}", runId, type);
            return;
        }
        QaEvent event = new QaEvent();
        event.setId(Ids.newId("qevt"));
        event.setRunId(runId);
        event.setSeq(seq);
        event.setType(type);
        event.setPayload(payload == null ? null : Json.write(payload));
        event.setCreatedAt(OffsetDateTime.now());
        eventMapper.insert(event);
    }

    private void markRunning(QaRun run) {
        QaRun update = new QaRun();
        update.setId(run.getId());
        update.setStatus(STATUS_RUNNING);
        if (run.getStartedAt() == null) {
            update.setStartedAt(OffsetDateTime.now());
        }
        update.setUpdatedAt(OffsetDateTime.now());
        runMapper.updateById(update);
    }

    private void finishSucceeded(QaRun run) {
        // 汇总节点的结果就是最终答案
        QaRunNode summary = listNodes(run.getId()).stream()
                .filter(node -> QaNodeSpec.SUMMARY.equals(node.getNodeKey()))
                .findFirst().orElse(null);
        Map<String, Object> result = summary == null ? Map.of() : Json.readMap(summary.getResult());
        String answer = str(result.get("answer"));
        String status = str(result.getOrDefault("status", STATUS_SUCCEEDED));
        String errorCode = str(result.get("errorCode"));
        String finalStatus = BranchAggregator.STATUS_INSUFFICIENT.equals(status)
                ? STATUS_INSUFFICIENT : STATUS_SUCCEEDED;

        // 显式 set null：updateById 会忽略 null 字段，
        // 上一次失败的错误码会留在库里，看起来像还没成功。
        runMapper.update(null, new LambdaUpdateWrapper<QaRun>()
                .eq(QaRun::getId, run.getId())
                .set(QaRun::getStatus, finalStatus)
                .set(QaRun::getAnswer, answer)
                .set(QaRun::getErrorCode, errorCode)
                .set(QaRun::getErrorMessage, null)
                .set(QaRun::getCompletedAt, OffsetDateTime.now())
                .set(QaRun::getUpdatedAt, OffsetDateTime.now()));

        // 助手消息落库：与同步 /qa/ask 的历史保持同一套结构。
        // 引用与分支明细要写成 JSON 字符串——直接 toString 会得到
        // "[{a=1, b=2}]" 这种 Java 字面量，前端解析不了。
        if (answer != null && !answer.isBlank()) {
            saveMessage(run.getConversationId(), "assistant", answer,
                    Json.write(result.get("citations")), Json.write(result.get("branches")), status);
        }
        emit(run.getId(), "run.finished", Map.of("status", finalStatus));
        log.info("问答完成 runId={} status={}", run.getId(), finalStatus);
    }

    private void finishFailed(QaRun run) {
        QaRunNode failed = listNodes(run.getId()).stream()
                .filter(node -> NODE_FAILED.equals(node.getStatus()))
                .findFirst().orElse(null);
        String errorCode = failed == null || failed.getErrorCode() == null
                ? "QA_RUN_FAILED" : failed.getErrorCode();
        String errorMessage = failed == null || failed.getErrorMessage() == null
                ? "节点执行失败" : failed.getErrorMessage();
        runMapper.update(null, new LambdaUpdateWrapper<QaRun>()
                .eq(QaRun::getId, run.getId())
                .set(QaRun::getStatus, STATUS_FAILED)
                .set(QaRun::getErrorCode, errorCode)
                .set(QaRun::getErrorMessage, errorMessage)
                .set(QaRun::getCompletedAt, OffsetDateTime.now())
                .set(QaRun::getUpdatedAt, OffsetDateTime.now()));
        // Map.of 不接受 null 值，失败节点可能为空，这里用可变 Map
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeKey", failed == null ? null : failed.getNodeKey());
        payload.put("errorCode", errorCode);
        emit(run.getId(), "run.failed", payload);
        log.warn("问答失败 runId={} node={}", run.getId(), failed == null ? "-" : failed.getNodeKey());
    }

    /** 冻结配置：提交那一刻的模型/检索参数。续跑用它，不读当前配置。 */
    private Map<String, Object> freezeConfig(String mode) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("mode", mode);
        config.put("branchTimeoutMs", properties.getQa().getBranchTimeout().toMillis());
        config.put("topK", properties.getRetrieval().getTopK());
        config.put("knowledgeBase", knowledgeBase.name());
        config.put("graphStore", graphService.storeName());
        return config;
    }

    /** 冻结上下文：检索一次，三路共用；取图/取指标的失败只降级不中断。 */
    private QaContextSnapshot freezeContext(String owner, ClassicDocument document, String question,
                                            String patientId) {
        List<KnowledgeBase.RetrievedChunk> hits = List.of();
        String retrieveError = null;
        try {
            hits = tracing.span("knowledge.retrieve", "retrieval", "knowledge-base",
                    Map.of("documentId", document.getId(), "topK", properties.getRetrieval().getTopK()),
                    () -> knowledgeBase.retrieve(document.getId(), question,
                            properties.getRetrieval().getTopK()));
        } catch (RuntimeException ex) {
            retrieveError = ex.getMessage();
            log.warn("问答上下文冻结时检索失败 doc={}: {}", document.getId(), ex.toString());
        }
        if (!hits.isEmpty()) {
            // 与即时问答同口径：患者绑定的库作为额外依据，冻结进快照，续跑不重查。
            hits = bindingService.augment(owner, patientId, question, hits,
                    properties.getRetrieval().getTopK());
        }
        GraphView graph = null;
        try {
            graph = tracing.span("graph.read", "graph", "graph-store",
                    Map.of("documentId", document.getId()),
                    () -> graphService.graph(document.getId()));
        } catch (RuntimeException ex) {
            log.warn("问答上下文冻结时取图失败 doc={}: {}", document.getId(), ex.toString());
        }
        List<PatientMetric> metrics = List.of();
        if (StringUtils.hasText(patientId)) {
            try {
                metrics = metricMapper.selectList(new LambdaQueryWrapper<PatientMetric>()
                        .eq(PatientMetric::getPatientId, patientId)
                        .orderByAsc(PatientMetric::getCode));
            } catch (RuntimeException ex) {
                log.warn("问答上下文冻结时取指标失败 patient={}: {}", patientId, ex.toString());
            }
        }
        return new QaContextSnapshot(document.getTitle(), hits, retrieveError, graph, metrics);
    }

    /**
     * 从冻结快照还原分支上下文。
     *
     * <p>用 {@link QaContextSnapshot} 做载体而不是裸 Map：Map 里的对象反序列化后会退化成
     * {@code LinkedHashMap}，强转回 {@code RetrievedChunk} 会直接 ClassCastException。
     */
    private BranchContext toBranchContext(QaRun run) {
        QaContextSnapshot snapshot = Json.read(run.getContextSnapshot(), QaContextSnapshot.class);
        if (snapshot == null) {
            // 快照丢了就退化成空上下文，让分支如实报"无依据"，而不是抛异常掩盖问题
            log.warn("问答运行缺少上下文快照 runId={}", run.getId());
            return new BranchContext(run.getDocumentId(), null, run.getQuestion(),
                    run.getPatientId(), List.of(), "上下文快照缺失", null, List.of());
        }
        return new BranchContext(run.getDocumentId(), snapshot.documentTitle(), run.getQuestion(),
                run.getPatientId(), snapshot.hits(), snapshot.retrieveError(),
                snapshot.graph(), snapshot.metrics());
    }

    /** 建节点：retrieval → 各分支 → summary。 */
    private void createNodes(QaRun run, String documentTitle) {
        List<AnswerBranch> branches = nodeExecutor.branches();
        List<QaRunNode> nodes = new ArrayList<>();
        int order = 0;

        QaRunNode retrieval = new QaRunNode();
        retrieval.setId(Ids.newId("qnode"));
        retrieval.setRunId(run.getId());
        retrieval.setNodeKey(QaNodeSpec.RETRIEVAL);
        retrieval.setLabel("检索与上下文准备");
        retrieval.setOrderIndex(order++);
        retrieval.setStatus(NODE_SUCCEEDED);
        retrieval.setAttemptCount(1);
        retrieval.setAutoAttemptLimit(1);
        retrieval.setResult(Json.write(Map.of("hits", 0, "note", "上下文已在提交时冻结")));
        retrieval.setStartedAt(run.getCreatedAt());
        retrieval.setCompletedAt(run.getCreatedAt());
        retrieval.setUpdatedAt(run.getCreatedAt());
        nodes.add(retrieval);

        for (AnswerBranch branch : branches) {
            QaRunNode node = new QaRunNode();
            node.setId(Ids.newId("qnode"));
            node.setRunId(run.getId());
            node.setNodeKey(branch.key());
            node.setLabel(branch.label());
            node.setOrderIndex(order++);
            node.setStatus(NODE_PENDING);
            node.setAttemptCount(0);
            node.setAutoAttemptLimit(properties.getQa().getNodeMaxAttempts());
            node.setUpdatedAt(run.getCreatedAt());
            nodes.add(node);
        }

        QaRunNode summary = new QaRunNode();
        summary.setId(Ids.newId("qnode"));
        summary.setRunId(run.getId());
        summary.setNodeKey(QaNodeSpec.SUMMARY);
        summary.setLabel("汇总");
        summary.setOrderIndex(order);
        summary.setStatus(NODE_PENDING);
        summary.setAttemptCount(0);
        summary.setAutoAttemptLimit(1);
        summary.setUpdatedAt(run.getCreatedAt());
        nodes.add(summary);

        for (QaRunNode node : nodes) {
            nodeMapper.insert(node);
        }
    }

    private List<QaRunNode> listNodes(String runId) {
        return nodeMapper.selectList(new LambdaQueryWrapper<QaRunNode>()
                .eq(QaRunNode::getRunId, runId)
                .orderByAsc(QaRunNode::getOrderIndex));
    }

    /**
     * 按归属人 + 幂等键查既有运行。
     *
     * <p>为什么不按会话查：会话是可选的（不传就新建），
     * 按会话查重会让"两次不带会话的同键提交"各自建出会话，幂等形同虚设。
     */
    private QaRun findByKey(String owner, String idempotencyKey) {
        return runMapper.selectOne(new LambdaQueryWrapper<QaRun>()
                .eq(QaRun::getOwner, owner)
                .eq(QaRun::getIdempotencyKey, idempotencyKey)
                .last("limit 1"));
    }

    /** 取运行并校验归属。越权返回 404。 */
    private QaRun requireOwned(String runId, String username) {
        QaRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ERROR_NOT_FOUND, "问答运行不存在", org.springframework.http.HttpStatus.NOT_FOUND);
        }
        String owner = run.getOwner();
        if (owner != null && !owner.equals(username == null ? "unknown" : username)) {
            throw new BizException(ERROR_NOT_FOUND, "问答运行不存在", org.springframework.http.HttpStatus.NOT_FOUND);
        }
        return run;
    }

    private QaConversation resolveConversation(String owner, ClassicDocument document,
                                               QaRunContract.SubmitRequest request, String question) {
        if (StringUtils.hasText(request.conversationId())) {
            QaConversation existing = conversationMapper.selectById(request.conversationId());
            if (existing == null) {
                throw BizException.notFound("会话");
            }
            if (existing.getCreatedBy() != null && !existing.getCreatedBy().equals(owner)) {
                throw BizException.notFound("会话");
            }
            if (!existing.getDocumentId().equals(document.getId())) {
                throw BizException.badRequest("该会话不属于指定文档，请新建会话");
            }
            return existing;
        }
        QaConversation conversation = new QaConversation();
        conversation.setId(Ids.newId("qcv"));
        conversation.setDocumentId(document.getId());
        String title = question.strip();
        conversation.setTitle(title.length() <= 60 ? title : title.substring(0, 60));
        conversation.setCreatedBy(owner);
        conversation.setCreatedAt(OffsetDateTime.now());
        conversationMapper.insert(conversation);
        return conversation;
    }

    private void saveMessage(String conversationId, String role, String content,
                             String citations, String branches, String status) {
        QaMessage message = new QaMessage();
        message.setId(Ids.newId("qms"));
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setCitations(citations);
        message.setBranches(branches);
        message.setStatus(status);
        message.setCreatedAt(OffsetDateTime.now());
        messageMapper.insert(message);
    }

    private String resolveMode(String requested) {
        String mode = StringUtils.hasText(requested) ? requested.strip().toLowerCase()
                : properties.getQa().getMode();
        if (!"single".equals(mode) && !"parallel".equals(mode)) {
            throw BizException.badRequest("mode 只支持 single 或 parallel，当前为 " + mode);
        }
        return mode;
    }

    private QaRunContract.AcceptedResponse accepted(QaRun run) {
        return new QaRunContract.AcceptedResponse(run.getId(), run.getConversationId(),
                run.getDocumentId(), run.getStatus(), run.getVersion(),
                "/api/v1/qa/runs/" + run.getId());
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
