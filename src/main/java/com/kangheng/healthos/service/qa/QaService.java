package com.kangheng.healthos.service.qa;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.kangheng.healthos.model.qa.Citation;
import com.kangheng.healthos.mapper.classics.ClassicDocumentMapper;
import com.kangheng.healthos.model.classics.ClassicDocument;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.spi.ai.LlmProvider;
import com.kangheng.healthos.model.graph.GraphView;
import com.kangheng.healthos.service.graph.GraphService;
import com.kangheng.healthos.mapper.patient.PatientMetricMapper;
import com.kangheng.healthos.model.patient.PatientMetric;
import com.kangheng.healthos.contract.qa.QaContract;
import com.kangheng.healthos.mapper.qa.QaConversationMapper;
import com.kangheng.healthos.mapper.qa.QaMessageMapper;
import com.kangheng.healthos.model.qa.QaConversation;
import com.kangheng.healthos.model.qa.QaMessage;
import com.kangheng.healthos.spi.qa.BranchContext;
import com.kangheng.healthos.service.qa.BranchOrchestrator;
import com.kangheng.healthos.spi.qa.BranchResult;
import com.kangheng.healthos.spi.retrieval.KnowledgeBase;
import com.kangheng.healthos.service.tracing.TracingService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 带引用的问答。
 *
 * <p>硬规则（来自原项目"无完整结果不发布综合回答"）：
 * <ul>
 *   <li>文档未解析完成时直接拒绝，不拿半成品内容作答；</li>
 *   <li>检索不到任何可用片段时，返回 INSUFFICIENT_EVIDENCE 并明确告知，不生成自由发挥的答案；</li>
 *   <li>答案与引用一起落库，历史消息回读时看到的是当时的出处，而不是重新检索的结果。</li>
 * </ul>
 *
 * <p>两种模式：
 * <ul>
 *   <li>{@code single}（默认）——单路原文证据，行为与引入并行之前完全一致；</li>
 *   <li>{@code parallel}——原文 / 中医图谱 / 现代医学指标三路并行后汇总，
 *       单路失败不影响其它路（详见 {@link BranchOrchestrator}）。</li>
 * </ul>
 * 模式由请求的 {@code mode} 决定，未传时取 {@code healthos.qa.mode}。
 */
@Service
public class QaService {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);

    public static final String STATUS_OK = "OK";
    public static final String STATUS_INSUFFICIENT = "INSUFFICIENT_EVIDENCE";
    public static final String MODE_SINGLE = "single";
    public static final String MODE_PARALLEL = "parallel";

    private static final String INSUFFICIENT_ANSWER =
            "该典籍的已解析内容中没有检索到与问题相关的原文片段，无法给出有依据的回答。"
                    + "建议换一种问法，或先确认该典籍的解析状态为 READY。";

    private final QaConversationMapper conversationMapper;
    private final QaMessageMapper messageMapper;
    private final ClassicDocumentMapper documentMapper;
    private final KnowledgeBase knowledgeBase;
    private final LlmProvider llmProvider;
    private final HealthOsProperties properties;
    private final BranchOrchestrator branchOrchestrator;
    private final GraphService graphService;
    private final PatientMetricMapper metricMapper;
    private final TracingService tracing;
    private final KnowledgeBindingService bindingService;

    public QaService(QaConversationMapper conversationMapper,
                     QaMessageMapper messageMapper,
                     ClassicDocumentMapper documentMapper,
                     KnowledgeBase knowledgeBase,
                     LlmProvider llmProvider,
                     HealthOsProperties properties,
                     BranchOrchestrator branchOrchestrator,
                     GraphService graphService,
                     PatientMetricMapper metricMapper,
                     TracingService tracing,
                     KnowledgeBindingService bindingService) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.documentMapper = documentMapper;
        this.knowledgeBase = knowledgeBase;
        this.llmProvider = llmProvider;
        this.properties = properties;
        this.branchOrchestrator = branchOrchestrator;
        this.graphService = graphService;
        this.metricMapper = metricMapper;
        this.tracing = tracing;
        this.bindingService = bindingService;
    }

    public QaContract.AskResponse ask(String username, QaContract.AskRequest request) {
        if (request == null || request.documentId() == null || request.documentId().isBlank()) {
            throw BizException.badRequest("documentId 不能为空");
        }
        String question = request.question() == null ? "" : request.question().strip();
        if (question.isEmpty()) {
            throw BizException.badRequest("question 不能为空");
        }
        ClassicDocument document = documentMapper.selectById(request.documentId());
        if (document == null) {
            throw BizException.notFound("典籍文档");
        }
        if (!"READY".equals(document.getStatus())) {
            throw BizException.conflict("文档尚未解析完成，当前状态为 " + document.getStatus());
        }

        String mode = resolveMode(request.mode());
        QaConversation conversation = resolveConversation(username, document, request, question);
        QaMessage userMessage = saveMessage(conversation.getId(), "user", question, null, null, STATUS_OK);

        tracing.beginTrace("qa_ask", document.getId(), username, request.patientId());
        tracing.event("qa_started", Map.of("mode", mode, "conversationId", conversation.getId(),
                "questionChars", question.length()));
        try {
            QaContract.AskResponse response = MODE_PARALLEL.equals(mode)
                    ? askParallel(document, conversation, userMessage, username, request, question)
                    : askSingle(document, conversation, userMessage, username, request, question);
            tracing.event("qa_answered", Map.of("status", response.status(),
                    "citations", response.citations() == null ? 0 : response.citations().size(),
                    "branches", response.branches() == null ? 0 : response.branches().size()));
            tracing.finishTrace(response.status(), response.errorCode(), null);
            return response;
        } catch (RuntimeException ex) {
            String code = ex instanceof BizException biz ? biz.getCode() : "QA_FAILED";
            tracing.finishTrace("FAILED", code, ex.getMessage());
            throw ex;
        }
    }

    // ---------------------------------------------------------------- 单路

    private QaContract.AskResponse askSingle(ClassicDocument document, QaConversation conversation,
                                             QaMessage userMessage, String username,
                                             QaContract.AskRequest request, String question) {
        List<KnowledgeBase.RetrievedChunk> documentHits = tracing.span("knowledge.retrieve", "retrieval",
                "knowledge-base",
                Map.of("documentId", document.getId(), "topK", properties.getRetrieval().getTopK()),
                () -> knowledgeBase.retrieve(document.getId(), question,
                        properties.getRetrieval().getTopK()));
        // 患者绑定的知识库作为额外依据。传了 patientId 才生效，未绑定时原样返回。
        List<KnowledgeBase.RetrievedChunk> hits = bindingService.augment(username, request.patientId(),
                question, documentHits, properties.getRetrieval().getTopK());
        if (hits.isEmpty()) {
            QaMessage assistant = saveMessage(conversation.getId(), "assistant",
                    INSUFFICIENT_ANSWER, Json.write(List.of()), null, STATUS_INSUFFICIENT);
            return new QaContract.AskResponse(conversation.getId(), userMessage.getId(),
                    assistant.getId(), INSUFFICIENT_ANSWER, STATUS_INSUFFICIENT,
                    List.of(), MODE_SINGLE, null, List.of());
        }

        String answer = tracing.span("llm.answer", "model", "llm",
                Map.of("provider", llmProvider.name(), "hits", hits.size()),
                () -> llmProvider.answer(
                        LlmProvider.RouteContext.of("PATIENT_QA", "qa_conversation", conversation.getId()),
                        question, contextBlock(hits)));
        if (answer == null || answer.isBlank()) {
            throw new IllegalStateException("模型返回空答案，已中止本次问答（未写入空回复）");
        }
        List<Citation> citations = citations(hits);
        QaMessage assistant = saveMessage(conversation.getId(), "assistant", answer,
                Json.write(citations), null, STATUS_OK);
        return new QaContract.AskResponse(conversation.getId(), userMessage.getId(),
                assistant.getId(), answer, STATUS_OK, citations, MODE_SINGLE, null, List.of());
    }

    // ---------------------------------------------------------------- 并行

    /**
     * 三路并行：检索、取图、取指标各做<b>一次</b>，三路分支共享，最后由编排器汇总。
     *
     * <p>预取的失败要分清楚：检索失败记进 {@code retrieveError} 让原文分支判 FAILED；
     * 取图失败只让图谱分支无图可用（返回无依据），不影响原文路。
     */
    private QaContract.AskResponse askParallel(ClassicDocument document, QaConversation conversation,
                                               QaMessage userMessage, String username,
                                               QaContract.AskRequest request,
                                               String question) {
        List<KnowledgeBase.RetrievedChunk> hits = List.of();
        String retrieveError = null;
        try {
            hits = tracing.span("knowledge.retrieve", "retrieval", "knowledge-base",
                    Map.of("documentId", document.getId(), "topK", properties.getRetrieval().getTopK()),
                    () -> knowledgeBase.retrieve(document.getId(), question,
                            properties.getRetrieval().getTopK()));
        } catch (RuntimeException ex) {
            retrieveError = ex.getMessage();
            log.warn("并行问答预取检索失败 doc={}: {}", document.getId(), ex.toString());
        }
        if (!hits.isEmpty()) {
            hits = bindingService.augment(username, request.patientId(), question, hits,
                    properties.getRetrieval().getTopK());
        }

        GraphView graph = null;
        try {
            graph = tracing.span("graph.read", "graph", "graph-store",
                    Map.of("documentId", document.getId()),
                    () -> graphService.graph(document.getId()));
        } catch (RuntimeException ex) {
            log.warn("并行问答预取图谱失败 doc={}: {}", document.getId(), ex.toString());
        }

        List<PatientMetric> metrics = List.of();
        if (request.patientId() != null && !request.patientId().isBlank()) {
            try {
                metrics = metricMapper.selectList(new LambdaQueryWrapper<PatientMetric>()
                        .eq(PatientMetric::getPatientId, request.patientId())
                        .orderByAsc(PatientMetric::getCode));
            } catch (RuntimeException ex) {
                log.warn("并行问答预取指标失败 patient={}: {}", request.patientId(), ex.toString());
            }
        }

        BranchContext context = new BranchContext(document.getId(), document.getTitle(), question,
                request.patientId(), hits, retrieveError, graph, metrics);
        BranchOrchestrator.Outcome outcome = branchOrchestrator.run(context);

        String answer = outcome.answer() == null || outcome.answer().isBlank()
                ? INSUFFICIENT_ANSWER : outcome.answer();
        List<QaContract.BranchView> branches = toViews(outcome.branches());
        QaMessage assistant = saveMessage(conversation.getId(), "assistant", answer,
                Json.write(outcome.citations()), Json.write(branches), outcome.status());
        return new QaContract.AskResponse(conversation.getId(), userMessage.getId(),
                assistant.getId(), answer, outcome.status(), outcome.citations(),
                MODE_PARALLEL, outcome.errorCode(), branches);
    }

    private List<QaContract.BranchView> toViews(List<BranchResult> results) {
        List<QaContract.BranchView> views = new ArrayList<>();
        for (BranchResult result : results) {
            views.add(new QaContract.BranchView(result.key(), result.label(), result.status(),
                    result.basis(), result.answer(), result.errorCode(), result.elapsedMs(),
                    result.note(), result.citations()));
        }
        return views;
    }

    // ---------------------------------------------------------------- 历史

    public List<QaContract.MessageView> messages(String conversationId, String username) {
        QaConversation conversation = requireConversation(conversationId, username);
        List<QaMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<QaMessage>()
                .eq(QaMessage::getConversationId, conversationId)
                .orderByAsc(QaMessage::getCreatedAt));
        List<QaContract.MessageView> views = new ArrayList<>();
        for (QaMessage message : messages) {
            views.add(new QaContract.MessageView(message.getId(), message.getRole(), message.getContent(),
                    message.getStatus(),
                    Json.readList(message.getCitations(), Citation.class),
                    Json.readList(message.getBranches(), QaContract.BranchView.class),
                    message.getCreatedAt()));
        }
        return views;
    }

    /** 可用分支自描述，供接口发现"这台机器上问答到底跑了几路"。 */
    public List<java.util.Map<String, Object>> branches() {
        return branchOrchestrator.descriptors();
    }

    // ---------------------------------------------------------------- 内部

    private String resolveMode(String requested) {
        String mode = requested == null || requested.isBlank()
                ? properties.getQa().getMode() : requested.strip().toLowerCase();
        if (!MODE_SINGLE.equals(mode) && !MODE_PARALLEL.equals(mode)) {
            throw BizException.badRequest("mode 只支持 single 或 parallel，当前为 " + mode);
        }
        return mode;
    }

    private QaConversation resolveConversation(String username, ClassicDocument document,
                                               QaContract.AskRequest request, String question) {
        if (request.conversationId() != null && !request.conversationId().isBlank()) {
            // 归属校验：会话是提问人的私事，别人的会话不能拿来接着问
            QaConversation existing = requireConversation(request.conversationId(), username);
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
        conversation.setCreatedBy(username == null ? "unknown" : username);
        conversation.setCreatedAt(OffsetDateTime.now());
        conversationMapper.insert(conversation);
        return conversation;
    }

    /**
     * 取会话并校验归属。
     *
     * <p>越权返回 404：会话里有完整的问答原文，403 等于承认它存在。
     */
    private QaConversation requireConversation(String conversationId, String username) {
        QaConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw BizException.notFound("会话");
        }
        String owner = conversation.getCreatedBy();
        if (owner != null && !owner.equals(username == null ? "unknown" : username)) {
            throw BizException.notFound("会话");
        }
        return conversation;
    }

    private QaMessage saveMessage(String conversationId, String role, String content,
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
        return message;
    }

    private String contextBlock(List<KnowledgeBase.RetrievedChunk> hits) {
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (KnowledgeBase.RetrievedChunk hit : hits) {
            builder.append('[').append(index++).append("] ")
                    .append(hit.content().strip()).append('\n');
        }
        return builder.toString();
    }

    private List<Citation> citations(List<KnowledgeBase.RetrievedChunk> hits) {
        List<Citation> citations = new ArrayList<>();
        for (KnowledgeBase.RetrievedChunk hit : hits) {
            citations.add(new Citation(
                    hit.chunkId(),
                    hit.content().strip(),
                    hit.page(),
                    hit.charStart(),
                    hit.charEnd(),
                    hit.score(),
                    hit.documentId()));
        }
        return citations;
    }
}
