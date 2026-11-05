package com.kangheng.healthos.service.qa;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.kangheng.healthos.common.AccessControl;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.contract.qa.QaAuxiliaryContract;
import com.kangheng.healthos.mapper.qa.KnowledgeBindingMapper;
import com.kangheng.healthos.mapper.qa.QaConversationMapper;
import com.kangheng.healthos.mapper.qa.QaDiagnosticMapper;
import com.kangheng.healthos.mapper.qa.QaRunMapper;
import com.kangheng.healthos.model.qa.KnowledgeBindingEntity;
import com.kangheng.healthos.model.qa.QaConversation;
import com.kangheng.healthos.model.qa.QaDiagnostic;
import com.kangheng.healthos.model.qa.QaRun;
import com.kangheng.healthos.service.outbox.OutboxService;
import com.kangheng.healthos.spi.retrieval.KnowledgeBase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 单路 RAG 诊断（对齐原工程 {@code qa_diagnostics.py} 的主干）。
 *
 * <p>它回答的不是"这个病怎么治"，而是"这个患者绑定的库，检索这一路到底通不通、
 * 命中了什么、答案有没有出处"。因此执行阶段<b>只跑一次检索</b>，不调模型、不汇总，
 * 也不会往问答历史里写消息。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li><b>提交即受理、异步执行</b>：落 {@code QUEUED} + outbox 事件同事务，
 *       避免"单据在、任务没派出去"；worker 侧才真正检索；</li>
 *   <li><b>检索依赖不可用/无命中一律降级，绝不抛异常导致 Outbox 无限重试</b>。
 *       RAGFlow 挂了是诊断要记录的<b>结论</b>（{@code no_evidence} / unknown），
 *       不是让调度器反复重试的故障；</li>
 *   <li><b>失败仍保留 draft</b>：片段摘要是已经拿到的"已完成结果"，对齐原工程
 *       "已完成结果保留"，不因为后续序列化出错就抹掉。</li>
 * </ol>
 *
 * <p>幂等分两层：{@code operationKey} 是全部入参的哈希，同 key 同参数直接返回既有；
 * 同一 {@code Idempotency-Key} 头换参数则明确 409，不静默开一次新诊断。
 */
@Service
public class QaDiagnosticService {

    private static final Logger log = LoggerFactory.getLogger(QaDiagnosticService.class);

    /** 派发诊断的 outbox 事件类型；全局唯一，不能与 qa.run.execute 等已有类型重复。 */
    public static final String EVENT_EXECUTE = "qa.diagnostic.execute";

    public static final String MODE = "ragflow_diagnostic";

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String ERROR_KEY_REQUIRED = "IDEMPOTENCY_KEY_REQUIRED";
    public static final String ERROR_BINDING_NOT_ACTIVE = "QA_BINDING_NOT_ACTIVE";
    public static final String ERROR_NOT_FOUND = "RAGFLOW_DIAGNOSTIC_NOT_FOUND";
    public static final String ERROR_IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    public static final String ERROR_STATE = "QA_DIAGNOSTIC_STATE";

    private static final Set<String> KNOWN_STATUS =
            Set.of(STATUS_QUEUED, STATUS_RUNNING, STATUS_SUCCEEDED, STATUS_FAILED);

    private static final String SOURCE_KIND = "ragflow_dataset";
    private static final int SNIPPET_LIMIT = 200;
    private static final int DRAFT_SNIPPET_LIMIT = 120;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final QaDiagnosticMapper diagnosticMapper;
    private final KnowledgeBindingMapper bindingMapper;
    private final QaConversationMapper conversationMapper;
    private final QaRunMapper runMapper;
    private final KnowledgeBase knowledgeBase;
    private final HealthOsProperties properties;
    private final OutboxService outboxService;

    public QaDiagnosticService(QaDiagnosticMapper diagnosticMapper,
                               KnowledgeBindingMapper bindingMapper,
                               QaConversationMapper conversationMapper,
                               QaRunMapper runMapper,
                               KnowledgeBase knowledgeBase,
                               HealthOsProperties properties,
                               OutboxService outboxService) {
        this.diagnosticMapper = diagnosticMapper;
        this.bindingMapper = bindingMapper;
        this.conversationMapper = conversationMapper;
        this.runMapper = runMapper;
        this.knowledgeBase = knowledgeBase;
        this.properties = properties;
        this.outboxService = outboxService;
    }

    // ================================================================ 提交

    /**
     * 受理一次诊断（202）。
     *
     * <p>幂等冲突的判定分两步：
     * <ol>
     *   <li>按 {@code operationKey} 精确命中 → 同参数直接返回既有，参数不同报 409
     *       （防御脏数据/历史行，正常不会触发）；</li>
     *   <li>因为 operationKey 把 question/expectedVersion 也哈希进去了，"同一个 Header
     *       换参数"不会命中同一个 key。所以再按 (owner, bindingId) 取出既有行，
     *       用<b>新 Header + 旧行参数</b>重算一次 operationKey：能对上，说明这个 Header
     *       之前已经用在另一组参数上 → 409。这样无需额外存一列原始 Header。</li>
     * </ol>
     */
    @Transactional
    public QaAuxiliaryContract.DiagnosticAccepted submit(String username, String bindingId,
                                                         String idempotencyKey,
                                                         QaAuxiliaryContract.DiagnosticRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new BizException(ERROR_KEY_REQUIRED, "缺少 Idempotency-Key 请求头",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (request == null || !StringUtils.hasText(request.question())) {
            throw BizException.badRequest("question 不能为空");
        }
        String owner = normalize(username);
        String question = request.question().strip();
        Integer expectedVersion = request.expectedVersion();
        String key = idempotencyKey.strip();

        KnowledgeBindingEntity binding = bindingMapper.selectById(bindingId);
        // 越权/不存在一律 404：403 等于承认"这个绑定确实存在"。
        if (binding == null || !AccessControl.sameUser(owner, binding.getOwner())) {
            throw BizException.notFound("知识库绑定");
        }
        if (!"ACTIVE".equals(binding.getStatus())) {
            throw new BizException(ERROR_BINDING_NOT_ACTIVE,
                    "知识库绑定当前状态为 " + binding.getStatus() + "，不可诊断", HttpStatus.CONFLICT);
        }

        String operationKey = operationKey(bindingId, question, expectedVersion, key);
        QaDiagnostic existing = diagnosticMapper.selectOne(new LambdaQueryWrapper<QaDiagnostic>()
                .eq(QaDiagnostic::getOperationKey, operationKey)
                .last("LIMIT 1"));
        if (existing != null) {
            if (!sameParams(existing, owner, bindingId, question, expectedVersion)) {
                throw conflict();
            }
            return accepted(existing);
        }

        List<QaDiagnostic> siblings = diagnosticMapper.selectList(new LambdaQueryWrapper<QaDiagnostic>()
                .eq(QaDiagnostic::getOwner, owner)
                .eq(QaDiagnostic::getBindingId, bindingId));
        for (QaDiagnostic row : siblings) {
            String replay = operationKey(bindingId, row.getQuestion(), row.getExpectedVersion(), key);
            if (replay.equals(row.getOperationKey())) {
                throw conflict();
            }
        }

        QaDiagnostic run = new QaDiagnostic();
        run.setId(Ids.newId("qdiag"));
        run.setOwner(owner);
        run.setPatientId(binding.getPatientId());
        run.setBindingId(bindingId);
        run.setQuestion(question);
        run.setOperationKey(operationKey);
        run.setStatus(STATUS_QUEUED);
        run.setExpectedVersion(expectedVersion);
        run.setCreatedAt(OffsetDateTime.now());
        // 请求体只有 question/expectedVersion，没有会话；列表接口却按会话查。
        // 取该患者最近一次问答运行的会话作为关联来源，取不到就留空（仍可按 runId 查详情）。
        run.setConversationId(latestConversationId(owner, binding.getPatientId()));
        diagnosticMapper.insert(run);

        // 事件与 run 同事务：不会出现"已受理但永远没人执行"的孤儿。
        outboxService.append("qa_diagnostic", run.getId(), EVENT_EXECUTE,
                Map.of("runId", run.getId()));
        return accepted(run);
    }

    // ================================================================ 执行（worker 侧）

    /**
     * 执行一次诊断。
     *
     * <p><b>本方法不向 Outbox 抛异常</b>：检索依赖挂了、返回空、甚至后续处理抛异常，
     * 都转成一条 SUCCEEDED(no_evidence) 或 FAILED 的落库结果。诊断的价值恰恰在于
     * 把"检索不通"这个事实记下来，而不是让调度器无限重试一个外部故障。
     *
     * <p>幂等：已 SUCCEEDED / FAILED 直接返回；RUNNING 视为上次中断、可接管续跑。
     */
    public void execute(String runId) {
        QaDiagnostic run = diagnosticMapper.selectById(runId);
        if (run == null) {
            // 行被删了再抛异常只会让事件反复重放，直接收尾。
            log.warn("诊断运行不存在，跳过执行 runId={}", runId);
            return;
        }
        if (STATUS_SUCCEEDED.equals(run.getStatus()) || STATUS_FAILED.equals(run.getStatus())) {
            return;
        }
        if (!KNOWN_STATUS.contains(run.getStatus())) {
            markFailed(run, ERROR_STATE, "诊断状态非法: " + run.getStatus());
            return;
        }

        String draft = null;
        try {
            markRunning(run);
            RetrievalOutcome outcome = retrieve(run);
            draft = draft(outcome);

            // 先单独落 draft：后续拼 result / 序列化万一失败，已完成的片段摘要仍在。
            QaDiagnostic partial = new QaDiagnostic();
            partial.setId(runId);
            partial.setDraft(draft);
            diagnosticMapper.updateById(partial);

            Map<String, Object> result = buildResult(outcome);
            QaDiagnostic done = new QaDiagnostic();
            done.setId(runId);
            done.setStatus(STATUS_SUCCEEDED);
            done.setResult(Json.write(result));
            done.setDraft(draft);
            done.setCompletedAt(OffsetDateTime.now());
            diagnosticMapper.updateById(done);
            log.info("诊断完成 runId={} retrievalStatus={}", runId, outcome.status());
        } catch (Exception ex) {
            log.warn("诊断执行异常，降级为 FAILED（保留已有 draft） runId={}: {}",
                    runId, ex.toString());
            try {
                markFailed(run, "QA_DIAGNOSTIC_EXECUTION_FAILED", ex.getMessage(), draft);
            } catch (Exception persistEx) {
                log.error("回写诊断失败状态也失败 runId={}", runId, persistEx);
            }
        }
    }

    // ================================================================ 查询

    /** 会话维度列表，键集分页（created_at / id 倒序），带会话归属校验。 */
    public QaAuxiliaryContract.DiagnosticPage list(String username, String conversationId,
                                                   String cursor, Integer limit) {
        String owner = normalize(username);
        QaConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !AccessControl.sameUser(owner, conversation.getCreatedBy())) {
            throw BizException.notFound("会话");
        }
        int capped = capLimit(limit);
        Cursor decoded = decodeCursor(cursor);

        LambdaQueryWrapper<QaDiagnostic> wrapper = new LambdaQueryWrapper<QaDiagnostic>()
                .eq(QaDiagnostic::getOwner, owner)
                .eq(QaDiagnostic::getConversationId, conversationId);
        if (decoded != null) {
            OffsetDateTime time = decoded.time();
            String id = decoded.id();
            wrapper.and(w -> w.lt(QaDiagnostic::getCreatedAt, time)
                    .or(x -> x.eq(QaDiagnostic::getCreatedAt, time).lt(QaDiagnostic::getId, id)));
        }
        wrapper.orderByDesc(QaDiagnostic::getCreatedAt)
                .orderByDesc(QaDiagnostic::getId)
                .last("LIMIT " + (capped + 1));

        List<QaDiagnostic> rows = diagnosticMapper.selectList(wrapper);
        boolean hasMore = rows.size() > capped;
        List<QaDiagnostic> page = hasMore ? rows.subList(0, capped) : rows;
        List<QaAuxiliaryContract.DiagnosticItem> items = new ArrayList<>();
        for (QaDiagnostic row : page) {
            items.add(new QaAuxiliaryContract.DiagnosticItem(row.getId(), row.getBindingId(),
                    row.getQuestion(), row.getStatus(), row.getCreatedAt()));
        }
        String nextCursor = hasMore && !page.isEmpty()
                ? encodeCursor(page.get(page.size() - 1).getCreatedAt(), page.get(page.size() - 1).getId())
                : null;
        return new QaAuxiliaryContract.DiagnosticPage(items, nextCursor);
    }

    /** 详情。越权/不存在一律 404；result 里的 evidence 拆成独立字段呈现。 */
    public QaAuxiliaryContract.DiagnosticDetail detail(String username, String runId) {
        QaDiagnostic run = requireOwned(runId, username);
        // Json.readMap 在空值时会返回不可变 Map，先复制一份才能 remove。
        Map<String, Object> result = new LinkedHashMap<>(Json.readMap(run.getResult()));
        Object evidenceRaw = result.remove("evidence");
        List<QaAuxiliaryContract.DiagnosticEvidence> evidence = toEvidence(evidenceRaw);
        QaAuxiliaryContract.DiagnosticError error = (run.getErrorCode() == null
                && run.getErrorMessage() == null) ? null
                : new QaAuxiliaryContract.DiagnosticError(run.getErrorCode(), run.getErrorMessage());
        return new QaAuxiliaryContract.DiagnosticDetail(run.getId(), run.getBindingId(), MODE,
                run.getQuestion(), run.getStatus(), run.getDraft(), result, error,
                run.getCreatedAt(), run.getStartedAt(), run.getCompletedAt(), evidence);
    }

    // ================================================================ 内部：检索与结果

    /**
     * 只跑单路检索：在绑定对应的一个数据集上查一次。
     *
     * <p>任何异常都吞成 {@code unknown} 空结果——外部依赖不可用是诊断的<b>结论</b>，
     * 不是要抛给 Outbox 的故障；不抛异常才能避免无限重试。
     */
    private RetrievalOutcome retrieve(QaDiagnostic run) {
        KnowledgeBindingEntity binding = bindingMapper.selectById(run.getBindingId());
        if (binding == null || !StringUtils.hasText(binding.getDatasetId())) {
            return new RetrievalOutcome(List.of(), "unknown", "绑定或数据集 ID 缺失");
        }
        String datasetId = binding.getDatasetId();
        try {
            List<KnowledgeBase.RetrievedChunk> hits = knowledgeBase.retrieveDatasets(
                    List.of(datasetId), run.getQuestion(), properties.getRetrieval().getTopK());
            if (hits == null || hits.isEmpty()) {
                return new RetrievalOutcome(List.of(), "no_hit", null);
            }
            return new RetrievalOutcome(hits, "hit", null);
        } catch (RuntimeException ex) {
            log.warn("单路诊断检索失败，降级为 unknown runId={} dataset={}: {}",
                    run.getId(), datasetId, ex.toString());
            return new RetrievalOutcome(List.of(), "unknown", ex.getMessage());
        }
    }

    /**
     * 组装 result Map。
     *
     * <p>字段语义对齐原工程 qa_diagnostics.py 的分类：
     * <ul>
     *   <li>hit → {@code retrieval_only}：只拿到检索证据，本诊断不调模型，answer 恒为空；</li>
     *   <li>no_hit → {@code no_evidence}：确实没命中，是正常结论不是故障；</li>
     *   <li>unknown → {@code model_unknown_retrieval}：检索状态未知（依赖不可用）。</li>
     * </ul>
     * 三种情况运行本身都是 SUCCEEDED，"降级"体现在 resultKind 而不是 status。
     */
    private Map<String, Object> buildResult(RetrievalOutcome outcome) {
        List<KnowledgeBase.RetrievedChunk> hits = outcome.hits();
        List<QaAuxiliaryContract.DiagnosticEvidence> evidence = evidence(hits);
        List<String> evidenceIds = new ArrayList<>();
        List<Integer> citedIndices = new ArrayList<>();
        for (int i = 0; i < evidence.size(); i++) {
            evidenceIds.add(evidence.get(i).evidenceId());
            citedIndices.add(i);
        }
        boolean usable = "hit".equals(outcome.status());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executionStatus", usable ? "SUCCEEDED"
                : ("no_hit".equals(outcome.status()) ? "NO_EVIDENCE" : "DEGRADED"));
        result.put("retrievalStatus", outcome.status());
        result.put("resultKind", usable ? "retrieval_only"
                : ("no_hit".equals(outcome.status()) ? "no_evidence" : "model_unknown_retrieval"));
        result.put("usableForSummary", usable);
        // 单路诊断不生成模型答案；片段摘要放在 draft，避免把"检索到的原文"伪装成"结论"。
        result.put("answer", "");
        result.put("chunks", hits.size());
        result.put("citationStatus", usable ? "cited" : "not_applicable");
        result.put("citedChunkIndices", usable ? citedIndices : List.of());
        result.put("retrievedChunkCount", hits.size());
        result.put("sourceKind", SOURCE_KIND);
        result.put("evidenceIds", evidenceIds);
        result.put("citedEvidenceIds", usable ? evidenceIds : List.of());
        if (outcome.error() != null) {
            result.put("retrievalError", outcome.error());
        }
        // evidence 明细随 result 一起落库（只有这一列是 JSON），详情接口再拆出来单独呈现。
        result.put("evidence", evidence);
        return result;
    }

    /** 命中片段 → 证据视图。正文截断，不把整篇原文塞进诊断结果。 */
    private List<QaAuxiliaryContract.DiagnosticEvidence> evidence(
            List<KnowledgeBase.RetrievedChunk> hits) {
        List<QaAuxiliaryContract.DiagnosticEvidence> result = new ArrayList<>();
        for (KnowledgeBase.RetrievedChunk chunk : hits) {
            String content = chunk.content() == null ? "" : chunk.content().strip();
            String chunkId = chunk.chunkId() == null ? "" : chunk.chunkId();
            result.add(new QaAuxiliaryContract.DiagnosticEvidence(
                    Ids.stableId("qde", chunk.documentId(), chunkId, Ids.sha256Hex(content)),
                    SOURCE_KIND,
                    (chunk.documentId() == null ? "" : chunk.documentId()) + " / " + chunkId,
                    Ids.sha256Hex(content),
                    truncate(content, SNIPPET_LIMIT)));
        }
        return result;
    }

    /** draft：检索片段摘要。失败时也保留——对齐原工程"已完成结果保留"。 */
    private String draft(RetrievalOutcome outcome) {
        StringBuilder builder = new StringBuilder();
        if ("hit".equals(outcome.status())) {
            builder.append("单路检索命中 ").append(outcome.hits().size()).append(" 条片段：\n");
            int index = 1;
            for (KnowledgeBase.RetrievedChunk chunk : outcome.hits()) {
                String content = chunk.content() == null ? "" : chunk.content().strip();
                builder.append('[').append(index++).append("] ")
                        .append(truncate(content, DRAFT_SNIPPET_LIMIT)).append('\n');
            }
        } else if ("no_hit".equals(outcome.status())) {
            builder.append("单路检索无命中（no_evidence）。");
        } else {
            builder.append("检索依赖不可用，降级为 no_evidence：")
                    .append(outcome.error() == null ? "unknown" : outcome.error());
        }
        return builder.toString();
    }

    private record RetrievalOutcome(List<KnowledgeBase.RetrievedChunk> hits, String status,
                                    String error) {
    }

    // ================================================================ 内部：状态与工具

    private void markRunning(QaDiagnostic run) {
        QaDiagnostic update = new QaDiagnostic();
        update.setId(run.getId());
        update.setStatus(STATUS_RUNNING);
        if (run.getStartedAt() == null) {
            update.setStartedAt(OffsetDateTime.now());
        }
        diagnosticMapper.updateById(update);
    }

    private void markFailed(QaDiagnostic run, String errorCode, String message) {
        markFailed(run, errorCode, message, null);
    }

    /** 失败只改状态/错误，不碰 result 与 draft——已完成的片段摘要必须留住。 */
    private void markFailed(QaDiagnostic run, String errorCode, String message, String draft) {
        QaDiagnostic update = new QaDiagnostic();
        update.setId(run.getId());
        update.setStatus(STATUS_FAILED);
        update.setErrorCode(errorCode);
        update.setErrorMessage(truncate(message, 500));
        if (draft != null) {
            update.setDraft(draft);
        }
        update.setCompletedAt(OffsetDateTime.now());
        diagnosticMapper.updateById(update);
    }

    private QaDiagnostic requireOwned(String runId, String username) {
        QaDiagnostic run = diagnosticMapper.selectById(runId);
        if (run == null || !AccessControl.sameUser(normalize(username), run.getOwner())) {
            throw new BizException(ERROR_NOT_FOUND, "诊断运行不存在", HttpStatus.NOT_FOUND);
        }
        return run;
    }

    private String latestConversationId(String owner, String patientId) {
        if (!StringUtils.hasText(patientId)) {
            return null;
        }
        QaRun run = runMapper.selectOne(new LambdaQueryWrapper<QaRun>()
                .eq(QaRun::getOwner, owner)
                .eq(QaRun::getPatientId, patientId)
                .orderByDesc(QaRun::getCreatedAt)
                .last("LIMIT 1"));
        return run == null ? null : run.getConversationId();
    }

    private QaAuxiliaryContract.DiagnosticAccepted accepted(QaDiagnostic run) {
        return new QaAuxiliaryContract.DiagnosticAccepted(run.getId(), run.getBindingId(),
                "/api/v1/qa/diagnostics/" + run.getId());
    }

    private String operationKey(String bindingId, String question, Integer expectedVersion,
                                String idempotencyKey) {
        return Ids.sha256Hex("ragflow_diagnostic|" + bindingId + "|" + question + "|"
                + expectedVersion + "|" + idempotencyKey);
    }

    private boolean sameParams(QaDiagnostic row, String owner, String bindingId,
                               String question, Integer expectedVersion) {
        return Objects.equals(row.getOwner(), owner)
                && Objects.equals(row.getBindingId(), bindingId)
                && Objects.equals(row.getQuestion(), question)
                && Objects.equals(row.getExpectedVersion(), expectedVersion);
    }

    private BizException conflict() {
        return new BizException(ERROR_IDEMPOTENCY_CONFLICT,
                "同一 Idempotency-Key 不能用于不同的诊断参数", HttpStatus.CONFLICT);
    }

    private List<QaAuxiliaryContract.DiagnosticEvidence> toEvidence(Object raw) {
        List<QaAuxiliaryContract.DiagnosticEvidence> result = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return result;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            result.add(new QaAuxiliaryContract.DiagnosticEvidence(
                    str(map.get("evidenceId")), str(map.get("sourceKind")), str(map.get("title")),
                    str(map.get("contentHash")), str(map.get("snippet"))));
        }
        return result;
    }

    private int capLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }

    private String normalize(String username) {
        return username == null ? "unknown" : username;
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    // ---------------------------------------------------------------- 游标

    private record Cursor(OffsetDateTime time, String id) {
    }

    private String encodeCursor(OffsetDateTime time, String id) {
        String raw = time + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String cursor) {
        if (!StringUtils.hasText(cursor)) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int split = raw.lastIndexOf('|');
            if (split <= 0 || split == raw.length() - 1) {
                throw new IllegalArgumentException("游标缺少分隔");
            }
            return new Cursor(OffsetDateTime.parse(raw.substring(0, split)), raw.substring(split + 1));
        } catch (RuntimeException ex) {
            throw new BizException(ERROR_STATE, "分页游标无效",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
}
