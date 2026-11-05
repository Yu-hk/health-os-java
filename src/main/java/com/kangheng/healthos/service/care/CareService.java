package com.kangheng.healthos.service.care;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import com.kangheng.healthos.contract.care.CareContract;
import com.kangheng.healthos.mapper.care.CareAuditMapper;
import com.kangheng.healthos.mapper.care.CareCheckinMapper;
import com.kangheng.healthos.mapper.care.CareDocumentMapper;
import com.kangheng.healthos.mapper.care.CarePatientStateMapper;
import com.kangheng.healthos.mapper.care.CareRevisionMapper;
import com.kangheng.healthos.mapper.care.CareSignatureMapper;
import com.kangheng.healthos.model.care.CareAudit;
import com.kangheng.healthos.model.care.CareCheckin;
import com.kangheng.healthos.model.care.CareDocument;
import com.kangheng.healthos.model.care.CarePatientState;
import com.kangheng.healthos.model.care.CareRevision;
import com.kangheng.healthos.model.care.CareSignature;
import com.kangheng.healthos.common.AccessControl;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.service.outbox.OutboxService;
import com.kangheng.healthos.mapper.patient.PatientIntakeExamMapper;
import com.kangheng.healthos.mapper.patient.PatientMapper;
import com.kangheng.healthos.mapper.patient.PatientMetricMapper;
import com.kangheng.healthos.model.patient.Patient;
import com.kangheng.healthos.model.patient.PatientIntakeExam;
import com.kangheng.healthos.model.patient.PatientMetric;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.kangheng.healthos.spi.ai.LlmProvider;
import com.kangheng.healthos.spi.care.CareSectionGenerator;

/**
 * 健康管理：报告签发与四周计划打卡。
 *
 * <p>对应原工程 {@code care_service.py} 的核心部分。原工程有 700 行，这里砍掉了
 * PDF 渲染、独立任务队列、模型路由与图谱计算，保留的是业务规则本身——
 * 那些规则才是"签发"这件事有意义的原因：
 *
 * <ol>
 *   <li><b>来源绑定</b>：报告只能从"已确认的体检录入单"生成，生成时把体检内容
 *       冻结成 {@code sourceSnapshot}。体检后续改了不影响已生成报告，
 *       因为"这份报告当时依据什么"必须能回溯；</li>
 *   <li><b>两道人工关卡</b>：内容由规则/模型生成（机器写），但签发必须由人做，
 *       且审核意见不少于 5 个有效字符——"随手点一下确认"不构成审核；</li>
 *   <li><b>发布守卫</b>：签发/确认前要检查当前生效的体检与报告是否还是这份草稿的基线，
 *       别人抢先发了新版本时，这份草稿就不能再发出去；</li>
 *   <li><b>幂等</b>：同一幂等键 + 同一输入 → 同一份文档；同一幂等键换了输入 → 明确报错，
 *       不静默复用；</li>
 *   <li><b>乐观锁</b>：改稿、签发、确认、打卡都带 {@code expectedRevision}，
 *       并发操作时后到的那次明确失败而不是覆盖。</li>
 * </ol>
 *
 * <p><b>owner 隔离</b>：全部查询按归属人过滤，越权返回 404（不返回 403——
 * 403 等于承认"这个 ID 确实存在"，那本身就是信息泄露）。
 */
@Service
public class CareService {

    private static final Logger log = LoggerFactory.getLogger(CareService.class);

    public static final String KIND_REPORT = "report";
    public static final String KIND_PLAN = "plan";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_SIGNED = "SIGNED";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    public static final String ERROR_REVISION_CONFLICT = "CARE_REVISION_CONFLICT";
    public static final String ERROR_STATE = "CARE_STATE_INVALID";
    public static final String ERROR_SIGNER = "CARE_SIGNER_NOT_ALLOWED";
    public static final String ERROR_SOURCE_CHANGED = "CARE_SOURCE_CHANGED";
    public static final String ERROR_BASE_CHANGED = "CARE_PUBLICATION_BASE_CHANGED";
    public static final String ERROR_ALREADY_SIGNED = "CARE_ALREADY_SIGNED";
    public static final String ERROR_CHECKIN_VERSION = "CARE_CHECKIN_VERSION_CONFLICT";
    public static final String ERROR_TASK_NOT_FOUND = "CARE_TASK_NOT_FOUND";
    public static final String ERROR_IDEMPOTENCY_MISMATCH = "CARE_IDEMPOTENCY_MISMATCH";

    /** 生成报告的 outbox 事件类型。 */
    public static final String EVENT_GENERATE = "care.generate";

    private final CareDocumentMapper documentMapper;
    private final CareRevisionMapper revisionMapper;
    private final CareAuditMapper auditMapper;
    private final CareSignatureMapper signatureMapper;
    private final CarePatientStateMapper stateMapper;
    private final CareCheckinMapper checkinMapper;
    private final PatientIntakeExamMapper intakeMapper;
    private final PatientMapper patientMapper;
    private final PatientMetricMapper metricMapper;
    private final CareSectionGenerator generator;
    private final OutboxService outboxService;
    private final HealthOsProperties properties;

    public CareService(CareDocumentMapper documentMapper,
                       CareRevisionMapper revisionMapper,
                       CareAuditMapper auditMapper,
                       CareSignatureMapper signatureMapper,
                       CarePatientStateMapper stateMapper,
                       CareCheckinMapper checkinMapper,
                       PatientIntakeExamMapper intakeMapper,
                       PatientMapper patientMapper,
                       PatientMetricMapper metricMapper,
                       CareSectionGenerator generator,
                       OutboxService outboxService,
                       HealthOsProperties properties) {
        this.documentMapper = documentMapper;
        this.revisionMapper = revisionMapper;
        this.auditMapper = auditMapper;
        this.signatureMapper = signatureMapper;
        this.stateMapper = stateMapper;
        this.checkinMapper = checkinMapper;
        this.intakeMapper = intakeMapper;
        this.patientMapper = patientMapper;
        this.metricMapper = metricMapper;
        this.generator = generator;
        this.outboxService = outboxService;
        this.properties = properties;
    }

    // ============================================================ 创建报告

    /**
     * 生成健康评估报告。
     *
     * <p>幂等：同一归属人 + 同一幂等键 → 返回同一份文档；同一幂等键但来源体检变了
     * → 报错（{@code CARE_IDEMPOTENCY_MISMATCH}），不静默复用旧文档。
     */
    @Transactional
    public CareContract.AcceptedResponse createReport(String owner,
                                                      CareContract.CreateReportRequest request) {
        PatientIntakeExam exam = intakeMapper.selectById(request.examId());
        if (exam == null || !AccessControl.sameUser(owner, exam.getOwner())) {
            throw BizException.notFound("体检记录");
        }
        if (!"CONFIRMED".equals(exam.getStatus())) {
            throw new BizException(ERROR_STATE,
                    "只能基于已确认的体检生成报告，当前状态为 " + exam.getStatus(),
                    HttpStatus.CONFLICT);
        }
        String patientId = exam.getPatientId();
        if (!StringUtils.hasText(patientId)) {
            throw new BizException(ERROR_STATE, "该体检记录尚未生成正式档案，无法生成报告",
                    HttpStatus.CONFLICT);
        }
        Patient patient = patientMapper.selectById(patientId);
        if (patient == null || !AccessControl.sameUser(owner, patient.getCreatedBy())) {
            throw BizException.notFound("患者档案");
        }

        String key = StringUtils.hasText(request.idempotencyKey())
                ? request.idempotencyKey().strip() : "report:" + request.examId();
        String fingerprint = fingerprint(Map.of("examId", request.examId(),
                "patientId", patientId,
                "examRevision", String.valueOf(exam.getRevision() == null ? 0 : exam.getRevision())));

        CareDocument existing = findByIdempotency(owner, KIND_REPORT, key);
        if (existing != null) {
            if (!fingerprint.equals(existing.getInputFingerprint())) {
                throw new BizException(ERROR_IDEMPOTENCY_MISMATCH,
                        "同一幂等键已用于另一份体检资料，请更换幂等键", HttpStatus.CONFLICT);
            }
            return new CareContract.AcceptedResponse(existing.getId(), existing.getStatus(), true);
        }

        Map<String, Object> snapshot = buildSnapshot(patient, exam);
        CareDocument document = new CareDocument();
        document.setId(Ids.newId("cdoc"));
        document.setOwner(owner);
        document.setKind(KIND_REPORT);
        document.setPatientId(patientId);
        document.setSourceExamId(exam.getId());
        document.setSourceExamRevision(exam.getRevision());
        document.setIdempotencyKey(key);
        document.setInputFingerprint(fingerprint);
        document.setRevision(1);
        document.setStatus(STATUS_DRAFT);
        document.setSourceSnapshot(Json.write(snapshot));
        document.setCreatedAt(OffsetDateTime.now());
        document.setUpdatedAt(OffsetDateTime.now());

        // 发布基线必须在入库<b>之前</b>定下来并一起落库：它记的是"这份草稿是基于哪一份
        // 生效版本起草的"。放在 insert 之后赋值，库里存的是 null——于是发布守卫
        // 永远判定"基线已变"，第二次签发必然被拒。
        CarePatientState state = requireState(patientId, owner);
        document.setParentDocumentId(state.getActiveReportId());
        documentMapper.insert(document);

        outboxService.append("care_document", document.getId(), EVENT_GENERATE,
                Map.of("documentId", document.getId()));
        log.info("报告已创建 doc={} owner={} exam={}", document.getId(), owner, exam.getId());
        return new CareContract.AcceptedResponse(document.getId(), document.getStatus(), false);
    }

    // ============================================================ 生成（worker 调用）

    /**
     * 生成分节内容。由 outbox worker 调用。
     *
     * <p>幂等：已生成完的直接返回；失败的允许重跑（覆盖旧内容）。
     */
    @Transactional
    public void generate(String documentId) {
        CareDocument document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BizException("CARE_DOCUMENT_NOT_FOUND", "文档不存在: " + documentId,
                    HttpStatus.NOT_FOUND);
        }
        if (STATUS_SIGNED.equals(document.getStatus()) || STATUS_ACTIVE.equals(document.getStatus())) {
            return;
        }
        Map<String, Object> snapshot = Json.readMap(document.getSourceSnapshot());
        List<CareSectionGenerator.MetricFact> facts =
                CareSectionGenerator.toFacts(metricFacts(document.getPatientId(), document.getOwner()));
        List<String> keys = KIND_PLAN.equals(document.getKind())
                ? CareContract.PLAN_KEYS : CareContract.REPORT_KEYS;

        Map<String, Object> sections = new LinkedHashMap<>();
        for (String key : keys) {
            String pipelineKey = KIND_PLAN.equals(document.getKind()) ? "HEALTH_PLAN" : "REPORT";
            LlmProvider.RouteContext route =
                    LlmProvider.RouteContext.of(pipelineKey, "care_document", document.getId());
            CareSectionGenerator.SectionResult result = generator.generate(route,
                    document.getKind(), key,
                    new CareSectionGenerator.GenerationContext(snapshot, facts, sections,
                            document.getStartDate()));
            if (!result.ok()) {
                // 一节生成失败就整体失败：宁可不出报告，也不要出一份缺节的报告
                document.setStatus(STATUS_DRAFT);
                document.setErrorCode("CARE_SECTION_GENERATION_FAILED");
                document.setErrorMessage("分节 " + key + " 生成失败: " + result.text());
                document.setUpdatedAt(OffsetDateTime.now());
                documentMapper.updateById(document);
                throw new IllegalStateException("分节生成失败: " + key + " — " + result.text());
            }
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("text", result.text());
            section.put("metricRefs", result.metricRefs());
            sections.put(key, section);
        }

        document.setSections(Json.write(sections));
        document.setAggregate(Json.write(project(document, sections, true)));
        // 清空错误字段必须用 update wrapper：MyBatis-Plus 默认 NOT_NULL 策略会跳过 null 字段，
        // 直接 setErrorCode(null) 是"清不掉"的假操作。
        documentMapper.update(null, new LambdaUpdateWrapper<CareDocument>()
                .eq(CareDocument::getId, documentId)
                .set(CareDocument::getSections, document.getSections())
                .set(CareDocument::getAggregate, document.getAggregate())
                .set(CareDocument::getErrorCode, null)
                .set(CareDocument::getErrorMessage, null)
                .set(CareDocument::getUpdatedAt, OffsetDateTime.now()));
        log.info("文档分节生成完成 doc={} kind={} sections={}", documentId, document.getKind(),
                sections.size());
    }

    // ============================================================ 改稿

    /**
     * 编辑单个分节。只有草稿能改——已签发的内容不能悄悄变。
     */
    @Transactional
    public CareContract.DocumentView editSection(String owner, String documentId, String key,
                                                   CareContract.EditSectionRequest request) {
        CareDocument document = require(documentId, owner);
        requireDraft(document);
        checkRevision(document, request.expectedRevision());
        List<String> validKeys = KIND_PLAN.equals(document.getKind())
                ? CareContract.PLAN_KEYS : CareContract.REPORT_KEYS;
        if (!validKeys.contains(key)) {
            throw BizException.badRequest("未知的分节: " + key);
        }
        Map<String, Object> sections = Json.readMap(document.getSections());
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("text", request.text());
        section.put("metricRefs", request.metricRefs() == null ? List.of() : request.metricRefs());
        sections.put(key, section);

        document.setSections(Json.write(sections));
        document.setAggregate(Json.write(project(document, sections, true)));
        bumpRevision(document);
        documentMapper.updateById(document);

        snapshotRevision(document, owner, "人工修改分节 " + key);
        audit(document, owner, "section_edited", "人工修改分节 " + key, key);
        log.info("分节已修改 doc={} key={} rev={}", documentId, key, document.getRevision());
        return view(document, owner);
    }

    // ============================================================ 签发

    /**
     * 签发报告。签发后同步生成对应的四周计划（草稿）。
     *
     * <p>前置条件一个都不能少：当前用户有签发权限、审核意见有效字符 ≥ 5、
     * 文档是生成完成的草稿、修订号匹配、发布基线未变。
     */
    @Transactional
    public CareContract.DocumentView sign(String owner, String documentId,
                                           CareContract.SignRequest request) {
        requireSigner(owner);
        CareDocument document = require(documentId, owner);
        requireKind(document, KIND_REPORT);
        checkRevision(document, request.expectedRevision());

        String comment = request.reviewComment() == null ? "" : request.reviewComment().strip();
        int effectiveChars = comment.replaceAll("\\s", "").length();
        int minChars = properties.getCare().getMinReviewCommentChars();
        if (effectiveChars < minChars) {
            throw new BizException("CARE_REVIEW_COMMENT_TOO_SHORT",
                    "审核意见至少需要 " + minChars + " 个有效字符，当前 " + effectiveChars + " 个",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        CareSignature prior = signatureMapper.selectOne(new LambdaQueryWrapper<CareSignature>()
                .eq(CareSignature::getDocumentId, documentId).last("limit 1"));
        if (prior != null) {
            // 重复签发：内容与意见完全一致视为同一请求的幂等重放，否则明确拒绝
            String hash = contentHash(document);
            if (prior.getExpectedRevision() == request.expectedRevision()
                    && prior.getReviewComment().equals(comment)
                    && prior.getContentHash().equals(hash)) {
                return view(document, owner);
            }
            throw new BizException(ERROR_ALREADY_SIGNED,
                    "该报告已签发，不能用不同内容重复签发", HttpStatus.CONFLICT);
        }
        requireDraft(document);
        requireComplete(document);
        CarePatientState state = publicationGuard(document, true);

        String startDate = normalizeStartDate(request.startDate());
        String contentHash = contentHash(document);
        bumpRevision(document);
        document.setStatus(STATUS_SIGNED);
        document.setSignedRevision(document.getRevision());
        document.setActiveRevision(document.getRevision());
        document.setStartDate(startDate);
        document.setUpdatedAt(OffsetDateTime.now());
        documentMapper.updateById(document);

        CareSignature signature = new CareSignature();
        signature.setId(Ids.newId("csig"));
        signature.setDocumentId(documentId);
        signature.setOwner(owner);
        signature.setSignedRevision(document.getRevision());
        signature.setExpectedRevision(request.expectedRevision());
        signature.setSigner(owner);
        signature.setReviewComment(comment);
        signature.setContentHash(contentHash);
        signature.setStartDate(startDate);
        signature.setKind("authenticated_identity_confirmation");
        signature.setSignedAt(OffsetDateTime.now());
        signatureMapper.insert(signature);

        // 签发报告时同步生成计划草稿：计划的依据就是这份报告
        CareDocument plan = newPlanFromReport(document, startDate, owner, state);
        signature.setPlanDocumentId(plan.getId());
        signatureMapper.updateById(signature);

        state.setActiveReportId(document.getId());
        state.setActiveReportRevision(document.getRevision());
        state.setVersion(state.getVersion() == null ? 1 : state.getVersion() + 1);
        state.setUpdatedAt(OffsetDateTime.now());
        stateMapper.updateById(state);

        snapshotRevision(document, owner, "签发报告");
        audit(document, owner, "report_signed", "签发报告，审核意见已记录", null);
        log.info("报告已签发 doc={} signer={} plan={}", documentId, owner, plan.getId());
        return view(document, owner);
    }

    // ============================================================ 修订

    /**
     * 基于已签发报告创建修订草稿。原报告保持不变，新草稿指向它。
     */
    @Transactional
    public CareContract.AcceptedResponse revise(String owner, String documentId,
                                                CareContract.ReviseRequest request) {
        CareDocument original = require(documentId, owner);
        requireKind(original, KIND_REPORT);
        checkRevision(original, request.expectedRevision());
        if (!STATUS_SIGNED.equals(original.getStatus())) {
            throw new BizException(ERROR_STATE, "只有已签发的报告才能创建修订草稿",
                    HttpStatus.CONFLICT);
        }
        requireComplete(original);
        CarePatientState state = publicationGuard(original, false);

        String key = "revise:" + original.getId() + ":" + request.expectedRevision();
        String fingerprint = fingerprint(Map.of("reportId", original.getId(),
                "revision", String.valueOf(request.expectedRevision()), "operation", "revise"));
        CareDocument existing = findByIdempotency(owner, KIND_REPORT, key);
        if (existing != null) {
            return new CareContract.AcceptedResponse(existing.getId(), existing.getStatus(), true);
        }

        CareDocument draft = new CareDocument();
        draft.setId(Ids.newId("cdoc"));
        draft.setOwner(owner);
        draft.setKind(KIND_REPORT);
        draft.setPatientId(original.getPatientId());
        draft.setSourceExamId(original.getSourceExamId());
        draft.setSourceExamRevision(original.getSourceExamRevision());
        // 修订来源：这一稿是从哪一份签发版本复制出来的。
        draft.setSourceReportId(original.getId());
        draft.setSourceReportRevision(original.getRevision());
        // 发布基线：起草时当前生效的版本。发布时基线变了就拒绝。
        draft.setParentDocumentId(state.getActiveReportId());
        draft.setIdempotencyKey(key);
        draft.setInputFingerprint(fingerprint);
        draft.setRevision(1);
        draft.setStatus(STATUS_DRAFT);
        draft.setSections(original.getSections());
        draft.setSourceSnapshot(original.getSourceSnapshot());
        draft.setCreatedAt(OffsetDateTime.now());
        draft.setUpdatedAt(OffsetDateTime.now());
        documentMapper.insert(draft);

        draft.setAggregate(Json.write(project(draft, Json.readMap(draft.getSections()), true)));
        documentMapper.updateById(draft);

        snapshotRevision(draft, owner, "从已签发版本复制可编辑草稿");
        audit(draft, owner, "report_revision_created", "从已签发报告创建修订草稿", null);
        log.info("修订草稿已创建 doc={} parent={}", draft.getId(), original.getId());
        return new CareContract.AcceptedResponse(draft.getId(), draft.getStatus(), false);
    }

    // ============================================================ 计划确认

    /**
     * 确认四周计划生效。只有生成完成的计划草稿可确认。
     */
    @Transactional
    public CareContract.DocumentView confirmPlan(String owner, String documentId,
                                                  CareContract.ConfirmPlanRequest request) {
        CareDocument document = require(documentId, owner);
        requireKind(document, KIND_PLAN);
        checkRevision(document, request.expectedRevision());
        if (STATUS_ACTIVE.equals(document.getStatus())) {
            // 已生效：同一请求幂等返回，历史计划则拒绝复活
            CarePatientState state = stateMapper.selectById(document.getPatientId());
            if (state != null && document.getId().equals(state.getActivePlanId())
                    && document.getRevision().equals(state.getActivePlanRevision())) {
                return view(document, owner);
            }
            throw new BizException(ERROR_BASE_CHANGED, "该历史计划已被新计划替代，不能重新激活",
                    HttpStatus.CONFLICT);
        }
        requireDraft(document);
        requireComplete(document);
        CarePatientState state = publicationGuard(document, true);

        bumpRevision(document);
        document.setStatus(STATUS_ACTIVE);
        document.setActiveRevision(document.getRevision());
        document.setUpdatedAt(OffsetDateTime.now());
        documentMapper.updateById(document);

        state.setActivePlanId(document.getId());
        state.setActivePlanRevision(document.getRevision());
        state.setVersion(state.getVersion() == null ? 1 : state.getVersion() + 1);
        state.setUpdatedAt(OffsetDateTime.now());
        stateMapper.updateById(state);

        snapshotRevision(document, owner, "人工确认四周计划生效");
        audit(document, owner, "plan_confirmed", "人工确认四周计划生效", null);
        log.info("计划已确认生效 doc={} owner={}", documentId, owner);
        return view(document, owner);
    }

    // ============================================================ 打卡

    /**
     * 打卡。只有<b>当前生效</b>的计划可打卡——已作废的历史计划不能补打卡。
     */
    @Transactional
    public CareContract.DocumentView checkin(String owner, String documentId, String taskId,
                                             CareContract.CheckinRequest request) {
        CareDocument document = require(documentId, owner);
        requireKind(document, KIND_PLAN);
        if (!STATUS_ACTIVE.equals(document.getStatus())) {
            throw new BizException(ERROR_STATE, "只有已确认生效的计划可打卡", HttpStatus.CONFLICT);
        }
        CarePatientState state = stateMapper.selectById(document.getPatientId());
        if (state == null || !document.getId().equals(state.getActivePlanId())
                || !document.getRevision().equals(state.getActivePlanRevision())) {
            throw new BizException(ERROR_BASE_CHANGED, "该计划不是当前生效的计划，不能打卡",
                    HttpStatus.CONFLICT);
        }
        if (!taskIds(document).contains(taskId)) {
            throw new BizException(ERROR_TASK_NOT_FOUND, "该计划中没有此任务", HttpStatus.NOT_FOUND);
        }
        boolean completed = Boolean.TRUE.equals(request.completed());

        CareCheckin row = checkinMapper.selectOne(new LambdaQueryWrapper<CareCheckin>()
                .eq(CareCheckin::getDocumentId, documentId)
                .eq(CareCheckin::getTaskId, taskId)
                .last("limit 1"));
        if (row != null) {
            int current = row.getVersion() == null ? 1 : row.getVersion();
            boolean same = Boolean.valueOf(completed).equals(row.getCompleted());
            if (same && (request.expectedVersion() == current || request.expectedVersion() == current - 1)) {
                // 幂等：同样的结果重复提交直接返回
                return view(document, owner);
            }
            if (current != request.expectedVersion()) {
                throw new BizException(ERROR_CHECKIN_VERSION,
                        "打卡记录已被他人更新，当前版本为 " + current + "，请刷新后重试",
                        HttpStatus.CONFLICT);
            }
            int updated = checkinMapper.update(null, new LambdaUpdateWrapper<CareCheckin>()
                    .eq(CareCheckin::getId, row.getId())
                    .eq(CareCheckin::getVersion, request.expectedVersion())
                    .set(CareCheckin::getCompleted, completed)
                    .set(CareCheckin::getVersion, current + 1)
                    .set(CareCheckin::getActor, owner)
                    .set(CareCheckin::getUpdatedAt, OffsetDateTime.now()));
            if (updated == 0) {
                throw new BizException(ERROR_CHECKIN_VERSION, "打卡记录已经变化，请刷新",
                        HttpStatus.CONFLICT);
            }
        } else {
            if (request.expectedVersion() != 0) {
                throw new BizException(ERROR_CHECKIN_VERSION,
                        "打卡记录不存在，当前版本为 0", HttpStatus.CONFLICT);
            }
            CareCheckin created = new CareCheckin();
            created.setId(Ids.newId("cchk"));
            created.setDocumentId(documentId);
            created.setOwner(owner);
            created.setTaskId(taskId);
            created.setCompleted(completed);
            created.setVersion(1);
            created.setActor(owner);
            created.setUpdatedAt(OffsetDateTime.now());
            checkinMapper.insert(created);
        }

        audit(document, owner, completed ? "task_completed" : "task_unchecked",
                "人工更新任务打卡：" + taskId, null);
        document.setAggregate(Json.write(project(document, Json.readMap(document.getSections()), true)));
        document.setUpdatedAt(OffsetDateTime.now());
        documentMapper.updateById(document);
        return view(document, owner);
    }

    // ============================================================ 查询

    /** 文档详情。 */
    public CareContract.DocumentView get(String owner, String documentId) {
        return view(require(documentId, owner), owner);
    }

    /** 文档列表，按归属人过滤。 */
    public List<CareContract.DocumentView> list(String owner, String kind, String patientId,
                                                 int limit) {
        LambdaQueryWrapper<CareDocument> query = new LambdaQueryWrapper<CareDocument>()
                .eq(CareDocument::getOwner, owner)
                .orderByDesc(CareDocument::getCreatedAt)
                .last("limit " + Math.max(1, Math.min(limit, 200)));
        if (StringUtils.hasText(kind)) {
            query.eq(CareDocument::getKind, kind);
        }
        if (StringUtils.hasText(patientId)) {
            query.eq(CareDocument::getPatientId, patientId);
        }
        return documentMapper.selectList(query).stream().map(doc -> view(doc, owner)).toList();
    }

    /** 修订历史。 */
    public List<CareContract.RevisionView> revisions(String owner, String documentId) {
        require(documentId, owner);
        return revisionMapper.selectList(new LambdaQueryWrapper<CareRevision>()
                        .eq(CareRevision::getDocumentId, documentId)
                        .orderByDesc(CareRevision::getRevision))
                .stream()
                .map(rev -> new CareContract.RevisionView(rev.getId(), rev.getRevision(),
                        rev.getActor(), rev.getReason(), rev.getCreatedAt()))
                .toList();
    }

    /** 审计流水。 */
    public List<CareContract.AuditView> audits(String owner, String documentId) {
        require(documentId, owner);
        return auditMapper.selectList(new LambdaQueryWrapper<CareAudit>()
                        .eq(CareAudit::getDocumentId, documentId)
                        .orderByDesc(CareAudit::getCreatedAt))
                .stream()
                .map(a -> new CareContract.AuditView(a.getId(), a.getRevision(), a.getActor(),
                        a.getAction(), a.getReason(), a.getChangedSection(), a.getCreatedAt()))
                .toList();
    }

    // ============================================================ 内部

    private CareDocument require(String documentId, String owner) {
        CareDocument document = documentMapper.selectById(documentId);
        if (document == null || !AccessControl.sameUser(owner, document.getOwner())) {
            // 越权一律 404：403 等于承认这个 ID 存在
            throw BizException.notFound("健康管理文档");
        }
        return document;
    }

    private CareDocument findByIdempotency(String owner, String kind, String key) {
        return documentMapper.selectOne(new LambdaQueryWrapper<CareDocument>()
                .eq(CareDocument::getOwner, owner)
                .eq(CareDocument::getKind, kind)
                .eq(CareDocument::getIdempotencyKey, key)
                .last("limit 1"));
    }

    private void requireSigner(String owner) {
        List<String> signers = properties.getCare().getSignerUsers();
        if (signers == null || !signers.contains(owner)) {
            throw new BizException(ERROR_SIGNER, "当前登录账号没有签发权限", HttpStatus.FORBIDDEN);
        }
    }

    private void requireKind(CareDocument document, String kind) {
        if (!kind.equals(document.getKind())) {
            throw new BizException(ERROR_STATE,
                    "文档类型为 " + document.getKind() + "，该操作仅适用于 " + kind,
                    HttpStatus.CONFLICT);
        }
    }

    private void requireDraft(CareDocument document) {
        if (!STATUS_DRAFT.equals(document.getStatus())) {
            throw new BizException(ERROR_STATE,
                    "当前状态为 " + document.getStatus() + "，只有草稿可以修改", HttpStatus.CONFLICT);
        }
    }

    private void requireComplete(CareDocument document) {
        Map<String, Object> aggregate = Json.readMap(document.getAggregate());
        if (!Boolean.TRUE.equals(aggregate.get("complete"))) {
            throw new BizException(ERROR_STATE, "内容尚未生成完成，不能发布", HttpStatus.CONFLICT);
        }
    }

    private void checkRevision(CareDocument document, Integer expected) {
        int actual = document.getRevision() == null ? 1 : document.getRevision();
        if (expected != null && expected != actual) {
            throw new BizException(ERROR_REVISION_CONFLICT,
                    "版本冲突：当前版本为 " + actual + "，请刷新后重试", HttpStatus.CONFLICT);
        }
    }

    private void bumpRevision(CareDocument document) {
        document.setRevision((document.getRevision() == null ? 1 : document.getRevision()) + 1);
        document.setUpdatedAt(OffsetDateTime.now());
    }

    /**
     * 发布守卫：确认这份草稿的基线仍然是当前的生效版本。
     *
     * <p>两个人同时基于同一份报告起草时，先签发的那份会让后一份的基线失效——
     * 后一份不能继续发出去，否则患者会同时有两份"生效报告"。
     */
    private CarePatientState publicationGuard(CareDocument document, boolean publishing) {
        CarePatientState state = requireState(document.getPatientId(), document.getOwner());
        String baseline = KIND_PLAN.equals(document.getKind())
                ? state.getActivePlanId() : state.getActiveReportId();
        if (publishing && !java.util.Objects.equals(baseline, document.getParentDocumentId())) {
            throw new BizException(ERROR_BASE_CHANGED,
                    "该草稿创建后已有新版本发布，请基于最新版本重新创建草稿", HttpStatus.CONFLICT);
        }
        if (KIND_PLAN.equals(document.getKind())) {
            String activeReport = state.getActiveReportId();
            if (!java.util.Objects.equals(activeReport, document.getSourceReportId())) {
                throw new BizException(ERROR_SOURCE_CHANGED,
                        "当前签发报告已更新，请使用最新报告生成的计划", HttpStatus.CONFLICT);
            }
        }
        return state;
    }

    private CarePatientState requireState(String patientId, String owner) {
        CarePatientState state = stateMapper.selectById(patientId);
        if (state == null) {
            state = new CarePatientState();
            state.setPatientId(patientId);
            state.setOwner(owner);
            state.setVersion(1);
            state.setUpdatedAt(OffsetDateTime.now());
            stateMapper.insert(state);
            return state;
        }
        if (!AccessControl.sameUser(owner, state.getOwner())) {
            throw BizException.notFound("患者档案");
        }
        return state;
    }

    /** 签发报告时生成对应的计划草稿。 */
    private CareDocument newPlanFromReport(CareDocument report, String startDate, String owner,
                                           CarePatientState state) {
        String key = "sign:" + report.getId() + ":" + report.getRevision();
        CareDocument plan = new CareDocument();
        plan.setId(Ids.newId("cdoc"));
        plan.setOwner(owner);
        plan.setKind(KIND_PLAN);
        plan.setPatientId(report.getPatientId());
        plan.setSourceExamId(report.getSourceExamId());
        plan.setSourceExamRevision(report.getSourceExamRevision());
        plan.setSourceReportId(report.getId());
        plan.setSourceReportRevision(report.getSignedRevision());
        // 计划自己的发布基线是"起草时当前生效的计划"，不是报告的基线：
        // 两次签发会生成两份计划草稿，后一份必须以第一份为基线才不会被同时生效。
        plan.setParentDocumentId(state.getActivePlanId());
        plan.setIdempotencyKey(key);
        plan.setInputFingerprint(fingerprint(Map.of("reportId", report.getId(),
                "reportRevision", String.valueOf(report.getSignedRevision()),
                "startDate", startDate)));
        plan.setRevision(1);
        plan.setStatus(STATUS_DRAFT);
        plan.setStartDate(startDate);
        plan.setSourceSnapshot(report.getSourceSnapshot());
        plan.setCreatedAt(OffsetDateTime.now());
        plan.setUpdatedAt(OffsetDateTime.now());
        documentMapper.insert(plan);

        outboxService.append("care_document", plan.getId(), EVENT_GENERATE,
                Map.of("documentId", plan.getId()));
        return plan;
    }

    /** 来源体检的不可变快照。 */
    private Map<String, Object> buildSnapshot(Patient patient, PatientIntakeExam exam) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        Map<String, Object> basic = new LinkedHashMap<>();
        basic.put("name", patient.getName());
        basic.put("gender", patient.getGender());
        basic.put("birthDate", patient.getBirthDate() == null ? null : patient.getBirthDate().toString());
        basic.put("riskLevel", patient.getRiskLevel());
        snapshot.put("basicInfo", basic);
        snapshot.put("examId", exam.getId());
        snapshot.put("examRevision", exam.getRevision());
        snapshot.put("extractor", exam.getExtractor());
        snapshot.put("aggregatedAt", OffsetDateTime.now().toString());
        return snapshot;
    }

    /** 已确认的指标事实。 */
    private List<Map<String, Object>> metricFacts(String patientId, String owner) {
        List<PatientMetric> metrics = metricMapper.selectList(new LambdaQueryWrapper<PatientMetric>()
                .eq(PatientMetric::getPatientId, patientId)
                .eq(PatientMetric::getOwner, owner)
                .orderByAsc(PatientMetric::getCode));
        List<Map<String, Object>> facts = new ArrayList<>();
        for (PatientMetric metric : metrics) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", metric.getCode());
            item.put("name", metric.getName());
            item.put("value", formatValue(metric.getValue()));
            item.put("unit", metric.getUnit());
            item.put("status", metric.getStatus());
            item.put("referenceLow", metric.getReferenceLow() == null ? null
                    : metric.getReferenceLow().toPlainString());
            item.put("referenceHigh", metric.getReferenceHigh() == null ? null
                    : metric.getReferenceHigh().toPlainString());
            facts.add(item);
        }
        return facts;
    }

    /** 汇总投影：完整度、任务清单、打卡进度。 */
    private Map<String, Object> project(CareDocument document, Map<String, Object> sections,
                                        boolean complete) {
        Map<String, Object> aggregate = new LinkedHashMap<>();
        aggregate.put("kind", document.getKind());
        aggregate.put("documentId", document.getId());
        aggregate.put("patientId", document.getPatientId());
        aggregate.put("revision", document.getRevision());
        aggregate.put("status", document.getStatus());
        aggregate.put("complete", complete);
        aggregate.put("generator", generator.name());
        aggregate.put("sections", sections);
        aggregate.put("tasks", tasks(document, sections));
        return aggregate;
    }

    /**
     * 计划的任务清单。
     *
     * <p>任务由计划分节派生，{@code taskId} 稳定（基于分节 key 与序号），
     * 这样重跑生成不会让已有打卡记录指向不存在的任务。
     */
    private List<Map<String, Object>> tasks(CareDocument document, Map<String, Object> sections) {
        List<Map<String, Object>> tasks = new ArrayList<>();
        if (!KIND_PLAN.equals(document.getKind())) {
            return tasks;
        }
        int index = 0;
        for (String key : CareContract.PLAN_KEYS) {
            Object raw = sections.get(key);
            if (!(raw instanceof Map<?, ?> map)) {
                continue;
            }
            Object text = map.get("text");
            if (text == null) {
                continue;
            }
            String taskId = "task_" + key;
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("taskId", taskId);
            task.put("section", key);
            task.put("title", CareContract.SECTION_TITLES.getOrDefault(key, key));
            task.put("content", text.toString());
            task.put("index", index++);
            tasks.add(task);
        }
        return tasks;
    }

    private List<String> taskIds(CareDocument document) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> task : tasks(document, Json.readMap(document.getSections()))) {
            Object taskId = task.get("taskId");
            if (taskId != null) {
                ids.add(taskId.toString());
            }
        }
        return ids;
    }

    /** 签发内容哈希：用于识别"是不是同一份内容重复提交"。 */
    private String contentHash(CareDocument document) {
        return fingerprint(Map.of("sections", String.valueOf(document.getSections()),
                "revision", String.valueOf(document.getRevision())));
    }

    private void snapshotRevision(CareDocument document, String actor, String reason) {
        // 快照必须反映文档的当前状态（含签发 SIGNED / 生效 ACTIVE 之后的 status）。
        // 否则"已发布修订"的快照会停留在签发前的 DRAFT，下游按快照 status 判定时会误判——
        // 例如记忆采集只认 SIGNED 的发布修订，会因快照 status=DRAFT 而拒绝写入。
        document.setAggregate(Json.write(project(document, Json.readMap(document.getSections()), true)));
        CareRevision revision = new CareRevision();
        revision.setId(Ids.newId("crev"));
        revision.setDocumentId(document.getId());
        revision.setRevision(document.getRevision());
        revision.setSnapshot(document.getAggregate());
        revision.setActor(actor);
        revision.setReason(reason);
        revision.setCreatedAt(OffsetDateTime.now());
        revisionMapper.insert(revision);
        // 同步回写文档的聚合列：避免文档与修订快照的 status 不一致（文档列是签发前存的旧值）。
        documentMapper.updateById(document);
    }

    private void audit(CareDocument document, String actor, String action, String reason,
                        String changedSection) {
        CareAudit audit = new CareAudit();
        audit.setId(Ids.newId("caud"));
        audit.setDocumentId(document.getId());
        audit.setRevision(document.getRevision());
        audit.setActor(actor);
        audit.setAction(action);
        audit.setReason(reason);
        audit.setChangedSection(changedSection);
        audit.setCreatedAt(OffsetDateTime.now());
        auditMapper.insert(audit);
    }

    private String normalizeStartDate(String value) {
        if (!StringUtils.hasText(value)) {
            return LocalDate.now().plusDays(properties.getCare().getDefaultPlanStartDays()).toString();
        }
        try {
            return LocalDate.parse(value.strip()).toString();
        } catch (RuntimeException ex) {
            throw BizException.badRequest("startDate 格式应为 yyyy-MM-dd，当前为 " + value);
        }
    }

    private static String fingerprint(Map<String, Object> value) {
        return Ids.sha256Hex(Json.write(value));
    }

    // ============================================================ 视图

    private CareContract.DocumentView view(CareDocument document, String owner) {
        Map<String, Object> sections = Json.readMap(document.getSections());
        Map<String, Object> aggregate = Json.readMap(document.getAggregate());
        List<String> keys = KIND_PLAN.equals(document.getKind())
                ? CareContract.PLAN_KEYS : CareContract.REPORT_KEYS;

        List<CareContract.SectionView> sectionViews = new ArrayList<>();
        for (String key : keys) {
            Object raw = sections.get(key);
            String text = null;
            List<String> refs = List.of();
            if (raw instanceof Map<?, ?> map) {
                Object t = map.get("text");
                text = t == null ? null : t.toString();
                Object r = map.get("metricRefs");
                if (r instanceof List<?> list) {
                    List<String> collected = new ArrayList<>();
                    for (Object item : list) {
                        collected.add(String.valueOf(item));
                    }
                    refs = collected;
                }
            }
            sectionViews.add(new CareContract.SectionView(key,
                    CareContract.SECTION_TITLES.getOrDefault(key, key), text, refs,
                    StringUtils.hasText(text)));
        }

        // 打卡进度
        List<CareContract.TaskView> taskViews = new ArrayList<>();
        int total = 0;
        int done = 0;
        if (KIND_PLAN.equals(document.getKind())) {
            List<Map<String, Object>> taskRows = tasks(document, sections);
            Map<String, CareCheckin> checkins = new LinkedHashMap<>();
            for (CareCheckin row : checkinMapper.selectList(new LambdaQueryWrapper<CareCheckin>()
                    .eq(CareCheckin::getDocumentId, document.getId()))) {
                checkins.put(row.getTaskId(), row);
            }
            for (Map<String, Object> task : taskRows) {
                String taskId = String.valueOf(task.get("taskId"));
                CareCheckin row = checkins.get(taskId);
                boolean completed = row != null && Boolean.TRUE.equals(row.getCompleted());
                total++;
                if (completed) {
                    done++;
                }
                taskViews.add(new CareContract.TaskView(taskId,
                        String.valueOf(task.getOrDefault("category", "")),
                        String.valueOf(task.get("title")),
                        String.valueOf(task.get("content")),
                        String.valueOf(task.getOrDefault("target", "")),
                        task.get("index") == null ? 0 : (int) task.get("index"),
                        completed, row == null ? 0 : row.getVersion()));
            }
        }
        CareContract.CheckinSummary summary = new CareContract.CheckinSummary(total, done, total - done);

        CareSignature signature = signatureMapper.selectOne(new LambdaQueryWrapper<CareSignature>()
                .eq(CareSignature::getDocumentId, document.getId()).last("limit 1"));
        CareContract.SignatureView signatureView = signature == null ? null
                : new CareContract.SignatureView(signature.getSigner(), signature.getReviewComment(),
                signature.getSignedRevision(), signature.getContentHash(), signature.getStartDate(),
                signature.getKind(), signature.getSignedAt());

        return new CareContract.DocumentView(document.getId(), document.getOwner(), document.getKind(),
                document.getPatientId(), document.getStatus(), document.getRevision(),
                document.getSignedRevision(), document.getActiveRevision(),
                document.getSourceExamId(), document.getSourceExamRevision(),
                document.getSourceReportId(), document.getSourceReportRevision(),
                document.getParentDocumentId(), document.getStartDate(),
                Boolean.TRUE.equals(aggregate.get("complete")), sectionViews, taskViews, summary,
                signatureView, CareContract.DISCLAIMER, document.getCreatedAt(),
                document.getUpdatedAt());
    }

    /**
     * 指标数值格式化：去掉多余的尾随零，但<b>不用科学计数法</b>。
     *
     * <p>{@code BigDecimal.stripTrailingZeros()} 对 {@code 56.0000} 会得到
     * {@code 5.6E+1}——直接 toString 就变成 "5.6E+1"，正文里出现这种字符串
     * 会让读者误判数值。所以这里显式走 {@code toPlainString()}。
     */
    static String formatValue(BigDecimal value) {
        if (value == null) {
            return "";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() < 0) {
            stripped = stripped.setScale(0, java.math.RoundingMode.UNNECESSARY);
        }
        return stripped.toPlainString();
    }
}
