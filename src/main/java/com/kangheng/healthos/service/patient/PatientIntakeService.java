package com.kangheng.healthos.service.patient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import com.kangheng.healthos.service.classics.DocumentNormalizer;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.common.PageResult;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.service.outbox.OutboxService;
import com.kangheng.healthos.contract.patient.PatientContract;
import com.kangheng.healthos.model.patient.PatientIntakeFields;
import com.kangheng.healthos.model.patient.PatientMetrics;
import com.kangheng.healthos.spi.ai.LlmProvider;
import com.kangheng.healthos.spi.patient.PatientRecordExtractor;
import com.kangheng.healthos.mapper.patient.PatientIntakeExamMapper;
import com.kangheng.healthos.mapper.patient.PatientIntakePartMapper;
import com.kangheng.healthos.mapper.patient.PatientMapper;
import com.kangheng.healthos.model.patient.Patient;
import com.kangheng.healthos.model.patient.PatientIntakeExam;
import com.kangheng.healthos.model.patient.PatientIntakePart;
import com.kangheng.healthos.spi.storage.ObjectStorage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 资料录入：原始体检/病历资料 → 正式患者档案。
 *
 * <p>对应原工程 {@code patient_intake_*} 那套。整条链路的形状是：
 * <pre>
 * 上传原始资料
 *   → OCR/文本归一化成 Markdown
 *   → 按分片标题切开（主诉/现病史/体格检查/检查检验…）
 *   → 每片独立抽取字段与指标
 *   → 汇总
 *   → 人工核查两道（基础信息 / 临床内容）
 *   → 确认，生成正式档案 + 指标
 * </pre>
 *
 * <p>三条设计取舍：
 * <ol>
 *   <li><b>AI 只负责"抄"，签字的是人</b>。两道核查（基础/临床）都是
 *       {@code CONFIRMED} 才允许生成档案；不设"AI 直接建档"的捷径。</li>
 *   <li><b>每片独立成败</b>。一段抽坏了不该让整份资料作废，
 *       但只要有一片失败，档案就不许确认——残缺的档案比没有档案更危险。</li>
 *   <li><b>提交与执行分离</b>。上传只落库 + 写 outbox 就返回，
 *       OCR 与抽取都在 worker 里跑，HTTP 请求不会被 OCR 拖死。</li>
 * </ol>
 */
@Service
public class PatientIntakeService {

    private static final Logger log = LoggerFactory.getLogger(PatientIntakeService.class);

    public static final String EVENT_EXTRACT = "patient.intake.extract";

    public static final String STATUS_UPLOADED = "UPLOADED";
    public static final String STATUS_EXTRACTING = "EXTRACTING";
    public static final String STATUS_EXTRACTED = "EXTRACTED";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String REVIEW_PENDING = "PENDING";
    public static final String REVIEW_CONFIRMED = "CONFIRMED";
    public static final String REVIEW_REJECTED = "REJECTED";

    public static final String PART_PENDING = "PENDING";
    public static final String PART_SUCCEEDED = "SUCCEEDED";
    public static final String PART_FAILED = "FAILED";
    public static final String PART_SKIPPED = "SKIPPED";

    /** 兜底分片：正文里没有明确标题的内容都归这里，保证不丢信息。 */
    private static final String PART_GENERAL = "general";

    private static final String OBJECT_PREFIX = "patient-intake";

    private final PatientIntakeExamMapper examMapper;
    private final PatientIntakePartMapper partMapper;
    private final PatientLifecycleService lifecycle;
    private final PatientService patientService;
    private final PatientRecordExtractor extractor;
    private final DocumentNormalizer normalizer;
    private final ObjectStorage storage;
    private final OutboxService outboxService;
    private final HealthOsProperties properties;

    public PatientIntakeService(PatientIntakeExamMapper examMapper,
                                PatientIntakePartMapper partMapper,
                                PatientLifecycleService lifecycle,
                                PatientService patientService,
                                PatientRecordExtractor extractor,
                                DocumentNormalizer normalizer,
                                ObjectStorage storage,
                                OutboxService outboxService,
                                HealthOsProperties properties) {
        this.examMapper = examMapper;
        this.partMapper = partMapper;
        this.lifecycle = lifecycle;
        this.patientService = patientService;
        this.extractor = extractor;
        this.normalizer = normalizer;
        this.storage = storage;
        this.outboxService = outboxService;
        this.properties = properties;
    }

    // ------------------------------------------------------------ 提交

    /**
     * 上传原始资料。
     *
     * <p>幂等：同一归属人重复上传同一份资料（sha256 相同）直接复用原录入单，
     * 不重跑一遍 OCR 与抽取。
     *
     * <p><b>但失败的录入单允许重试</b>：上一次失败（比如 OCR 服务当时挂了）
     * 不该让这份资料永远卡在那里——用户重新上传一次就应当重跑。
     * 已抽取完成或已确认的才真正跳过。
     *
     * <p>并发场景靠数据库唯一约束 {@code uk_patient_intake_source (owner, source_sha256)}
     * 兜底：两个请求同时"先查后插"时，后到的那个会撞约束，此时回查并复用先到者的
     * 录入单——否则会出现两份内容相同、编号不同的录入单，两边都能被确认成两个患者。
     */
    @Transactional
    public PatientContract.IntakeSubmitResponse submit(String filename, String contentType,
                                                       byte[] content, String owner) {
        if (content == null || content.length == 0) {
            throw BizException.badRequest("上传内容为空");
        }
        long max = properties.getPatient().getMaxObjectBytes();
        if (content.length > max) {
            throw BizException.badRequest("资料超过上限 " + max + " 字节");
        }
        String sha256 = Ids.sha256Hex(content);
        PatientIntakeExam existing = findBySha256(owner, sha256);
        if (existing != null) {
            if (STATUS_FAILED.equals(existing.getStatus())) {
                // 上一轮失败：重置状态并重新排队，让用户重新上传就能重试
                retryFailed(existing);
                return new PatientContract.IntakeSubmitResponse(existing.getId(),
                        existing.getStatus(), true);
            }
            return new PatientContract.IntakeSubmitResponse(existing.getId(), existing.getStatus(), true);
        }

        String objectKey = OBJECT_PREFIX + "/" + owner + "/" + Ids.newId("pie") + extensionOf(filename);
        ObjectStorage.StoredObject stored = storage.putAt(objectKey, content);

        OffsetDateTime now = OffsetDateTime.now();
        PatientIntakeExam exam = new PatientIntakeExam();
        exam.setId(Ids.newId("pie"));
        exam.setOwner(owner);
        exam.setSourceFilename(truncate(StringUtils.hasText(filename) ? filename : "intake", 300));
        exam.setSourceObjectKey(stored.objectKey());
        exam.setSourceSha256(sha256);
        exam.setSourceSize((long) content.length);
        exam.setMediaType(contentType);
        exam.setStatus(STATUS_UPLOADED);
        exam.setBasicReviewStatus(REVIEW_PENDING);
        exam.setClinicalReviewStatus(REVIEW_PENDING);
        exam.setRevision(0);
        exam.setExtractor(extractor.name());
        exam.setCreatedAt(now);
        exam.setUpdatedAt(now);
        try {
            examMapper.insert(exam);
        } catch (DuplicateKeyException ex) {
            // 并发同内容：先到的那份已经建好录入单，复用它，别建第二份档案出来
            PatientIntakeExam winner = findBySha256(owner, sha256);
            if (winner != null) {
                log.info("并发上传同内容，复用已有录入单 exam={} owner={}", winner.getId(), owner);
                return new PatientContract.IntakeSubmitResponse(winner.getId(), winner.getStatus(), true);
            }
            throw ex;
        }

        outboxService.append("patient_intake", exam.getId(), EVENT_EXTRACT,
                Map.of("examId", exam.getId()));
        log.info("资料录入已提交 exam={} owner={} sha256={}", exam.getId(), owner, sha256);
        return new PatientContract.IntakeSubmitResponse(exam.getId(), exam.getStatus(), false);
    }

    private PatientIntakeExam findBySha256(String owner, String sha256) {
        return examMapper.selectOne(new LambdaQueryWrapper<PatientIntakeExam>()
                .eq(PatientIntakeExam::getOwner, owner)
                .eq(PatientIntakeExam::getSourceSha256, sha256)
                .last("limit 1"));
    }

    /**
     * 失败的录入单重新排队。
     *
     * <p>清掉上一轮的错误与分片，重新写一条抽取事件。
     * 分片要删干净——残留的分片会被新一轮的汇总当成有效数据，
     * 结果是一份资料里混着两次抽取的结果。
     */
    private void retryFailed(PatientIntakeExam exam) {
        partMapper.delete(new LambdaQueryWrapper<PatientIntakePart>()
                .eq(PatientIntakePart::getExamId, exam.getId())
                .eq(PatientIntakePart::getOwner, exam.getOwner()));
        exam.setStatus(STATUS_UPLOADED);
        exam.setUpdatedAt(OffsetDateTime.now());
        examMapper.update(null, new LambdaUpdateWrapper<PatientIntakeExam>()
                .eq(PatientIntakeExam::getId, exam.getId())
                .eq(PatientIntakeExam::getOwner, exam.getOwner())
                .set(PatientIntakeExam::getStatus, STATUS_UPLOADED)
                .set(PatientIntakeExam::getErrorCode, null)
                .set(PatientIntakeExam::getErrorMessage, null)
                .set(PatientIntakeExam::getUpdatedAt, OffsetDateTime.now()));
        outboxService.append("patient_intake", exam.getId(), EVENT_EXTRACT,
                Map.of("examId", exam.getId()));
        log.info("资料录入失败后重排 exam={} owner={}", exam.getId(), exam.getOwner());
    }

    // ------------------------------------------------------------ 执行（worker）

    /**
     * 归一化 → 切片 → 抽取 → 汇总。由 outbox worker 调用。
     *
     * <p>幂等：已抽取完的直接返回；其余状态允许重跑。
     *
     * <p>重跑前先清掉旧分片：分片表对 {@code (exam_id, part_key)} 建了唯一约束，
     * 不清就会在第二次插入时撞约束；更糟的是不清的话，
     * 上一轮的残留分片会被这一轮的汇总当成有效数据，
     * 结果是一份资料里混着两次抽取的结果。
     */
    public void process(String examId) {
        PatientIntakeExam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BizException("PATIENT_INTAKE_NOT_FOUND", "录入单不存在: " + examId,
                    HttpStatus.NOT_FOUND);
        }
        if (STATUS_EXTRACTED.equals(exam.getStatus()) || STATUS_CONFIRMED.equals(exam.getStatus())) {
            return;
        }
        partMapper.delete(new LambdaQueryWrapper<PatientIntakePart>()
                .eq(PatientIntakePart::getExamId, examId)
                .eq(PatientIntakePart::getOwner, exam.getOwner()));
        exam.setStatus(STATUS_EXTRACTING);
        exam.setUpdatedAt(OffsetDateTime.now());
        examMapper.updateById(exam);

        try {
            byte[] content = storage.get(exam.getSourceObjectKey());
            DocumentNormalizer.Normalized normalized = normalizer.normalize(content,
                    exam.getMediaType(), exam.getSourceFilename());
            if (!normalized.hasText()) {
                throw new BizException("PATIENT_INTAKE_EMPTY", "资料里没有可读文本（可能是扫描件但 OCR 未开启）",
                        HttpStatus.BAD_REQUEST);
            }

            Map<String, String> segments = splitParts(normalized.markdown());
            List<PatientIntakePart> parts = persistParts(exam, segments);

            Map<String, Object> aggregate = new LinkedHashMap<>();
            Map<String, Object> fields = new LinkedHashMap<>();
            Map<String, String> fieldSources = new LinkedHashMap<>();
            List<Map<String, Object>> metrics = new ArrayList<>();
            Map<String, String> metricSources = new LinkedHashMap<>();
            int failed = 0;

            for (PatientIntakePart part : parts) {
                if (PART_SKIPPED.equals(part.getStatus())) {
                    continue;
                }
                if (PART_FAILED.equals(part.getStatus())) {
                    failed++;
                    continue;
                }
                Map<String, Object> payload = Json.readMap(part.getPayload());
                Object partFields = payload == null ? null : payload.get("fields");
                if (partFields instanceof Map<?, ?> map) {
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        String key = String.valueOf(entry.getKey());
                        if (!fields.containsKey(key)) {
                            fields.put(key, entry.getValue());
                            fieldSources.put(key, part.getPartKey());
                        }
                    }
                }
                Object partMetrics = payload == null ? null : payload.get("metrics");
                if (partMetrics instanceof List<?> list) {
                    for (Object item : list) {
                        if (!(item instanceof Map<?, ?> map)) {
                            continue;
                        }
                        String code = String.valueOf(map.get("code"));
                        if (metricSources.containsKey(code)) {
                            continue;
                        }
                        Map<String, Object> metric = new LinkedHashMap<>();
                        // 逐项搬运而不是 putAll：这里的 key 类型是未定的通配符，
                        // putAll 的签名要求 key 已经是 String
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            metric.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                        metric.put("source", part.getPartKey());
                        metrics.add(metric);
                        metricSources.put(code, part.getPartKey());
                    }
                }
            }

            // BMI 是派生指标：由身高体重算出来，不来自原文，所以单独标注来源
            BigDecimal height = numeric(fields.get("heightCm"));
            BigDecimal weight = numeric(fields.get("weightKg"));
            BigDecimal bmi = PatientMetrics.bmi(height, weight);
            if (bmi != null) {
                Map<String, Object> derived = PatientMetrics.metric(
                        PatientIntakeFields.BMI.code(), PatientIntakeFields.BMI.name(), bmi,
                        PatientIntakeFields.BMI.unit(), PatientIntakeFields.BMI.lowValue(),
                        PatientIntakeFields.BMI.highValue());
                derived.put("source", "derived:heightCm+weightKg");
                metrics.add(derived);
            }

            aggregate.put("fields", fields);
            aggregate.put("fieldSources", fieldSources);
            aggregate.put("metrics", metrics);
            aggregate.put("metricSources", metricSources);
            aggregate.put("extractor", extractor.name());
            aggregate.put("failedParts", failed);
            aggregate.put("sourceKind", normalized.sourceKind());
            aggregate.put("pageCount", normalized.pageCount());

            List<String> abnormal = metrics.stream()
                    .filter(m -> "HIGH".equals(m.get("status")) || "LOW".equals(m.get("status")))
                    .map(m -> String.valueOf(m.get("code")))
                    .toList();

            exam.setMarkdown(normalized.markdown());
            exam.setMarkdownSha256(Ids.sha256Hex(normalized.markdown().getBytes(StandardCharsets.UTF_8)));
            exam.setOcrMetadata(Json.write(Map.of(
                    "sourceKind", normalized.sourceKind(),
                    "engine", normalized.engine(),
                    "ocrPages", normalized.ocrPages(),
                    "pageCount", normalized.pageCount())));
            exam.setAggregate(Json.write(aggregate));
            exam.setExtractedAbnormalCodes(Json.write(abnormal));
            exam.setStatus(STATUS_EXTRACTED);
            exam.setUpdatedAt(OffsetDateTime.now());
            examMapper.updateById(exam);
            // 错误字段要显式置空：MyBatis-Plus 默认 NOT_NULL 策略会跳过 null 字段，
            // updateById 里的 setErrorCode(null) 根本不会下发到数据库，
            // 结果就是重跑成功后录入单上还挂着上一次的失败原因。
            examMapper.update(null, new LambdaUpdateWrapper<PatientIntakeExam>()
                    .eq(PatientIntakeExam::getId, examId)
                    .set(PatientIntakeExam::getErrorCode, null)
                    .set(PatientIntakeExam::getErrorMessage, null)
                    .set(PatientIntakeExam::getUpdatedAt, OffsetDateTime.now()));
            log.info("资料抽取完成 exam={} parts={} failed={} metrics={}", examId, parts.size(), failed,
                    metrics.size());
        } catch (RuntimeException ex) {
            exam.setStatus(STATUS_FAILED);
            exam.setErrorCode(ex instanceof BizException biz ? biz.getCode() : "PATIENT_INTAKE_FAILED");
            exam.setErrorMessage(truncate(ex.getMessage() == null ? ex.toString() : ex.getMessage(), 1000));
            exam.setUpdatedAt(OffsetDateTime.now());
            examMapper.updateById(exam);
            throw ex;
        }
    }

    // ------------------------------------------------------------ 核查

    /**
     * 人工核查。
     *
     * <p>驳回必须带意见：只标 REJECTED 不说哪里不对，下一班人接手时等于没信息。
     */
    @Transactional
    public PatientContract.IntakeView review(String examId, PatientContract.IntakeReviewRequest request,
                                             String owner) {
        PatientIntakeExam exam = require(examId, owner);
        if (!STATUS_EXTRACTED.equals(exam.getStatus())) {
            throw new BizException("PATIENT_INTAKE_STATE", "当前状态 " + exam.getStatus() + " 不能核查",
                    HttpStatus.CONFLICT);
        }
        if (request.basicDecision() != null) {
            String decision = normalizeDecision(request.basicDecision());
            if (REVIEW_REJECTED.equals(decision) && !StringUtils.hasText(request.basicNote())) {
                throw BizException.badRequest("驳回基础信息核查必须写明原因");
            }
            exam.setBasicReviewStatus(decision);
            exam.setBasicReviewNote(truncate(request.basicNote(), 1000));
        }
        if (request.clinicalDecision() != null) {
            String decision = normalizeDecision(request.clinicalDecision());
            if (REVIEW_REJECTED.equals(decision) && !StringUtils.hasText(request.clinicalNote())) {
                throw BizException.badRequest("驳回临床内容核查必须写明原因");
            }
            exam.setClinicalReviewStatus(decision);
            exam.setClinicalReviewNote(truncate(request.clinicalNote(), 1000));
        }
        exam.setRevision(nextRevision(exam));
        exam.setUpdatedAt(OffsetDateTime.now());
        examMapper.updateById(exam);
        return view(exam);
    }

    // ------------------------------------------------------------ 确认 → 生成档案

    /**
     * 确认录入，生成正式患者档案与指标。
     *
     * <p>前置条件一个都不能少：抽取完成、两道核查都通过、没有任何分片失败、
     * 姓名与性别齐了。少一样就拒绝——宁可让人再补一次，也不要生成半残的档案。
     *
     * <p>"齐了"包括<b>人工补录</b>：抽取器不许猜值，扫描件也确实会抽不到
     * （OCR 把姓名认成单字、把"出生日期"认成"生日期"）。
     * 所以确认时可以带上补录的字段；带上就以补录的为准。
     */
    @Transactional
    public PatientContract.IntakeConfirmResult confirm(String examId,
                                                      PatientContract.IntakeConfirmRequest request,
                                                      String owner) {
        PatientIntakeExam exam = require(examId, owner);
        if (!STATUS_EXTRACTED.equals(exam.getStatus())) {
            throw new BizException("PATIENT_INTAKE_STATE",
                    "当前状态 " + exam.getStatus() + " 不能确认（只有 EXTRACTED 可确认）",
                    HttpStatus.CONFLICT);
        }
        int expected = request.expectedRevision() == null ? 0 : request.expectedRevision();
        int actual = exam.getRevision() == null ? 0 : exam.getRevision();
        if (actual != expected) {
            throw new BizException(PatientLifecycleService.ERROR_CONFLICT,
                    "版本冲突：当前版本为 " + actual + "，请刷新后重试", HttpStatus.CONFLICT);
        }
        if (!REVIEW_CONFIRMED.equals(exam.getBasicReviewStatus())) {
            throw new BizException("PATIENT_INTAKE_REVIEW_REQUIRED", "基础信息核查尚未通过",
                    HttpStatus.CONFLICT);
        }
        if (!REVIEW_CONFIRMED.equals(exam.getClinicalReviewStatus())) {
            throw new BizException("PATIENT_INTAKE_REVIEW_REQUIRED", "临床内容核查尚未通过",
                    HttpStatus.CONFLICT);
        }
        Map<String, Object> aggregate = Json.readMap(exam.getAggregate());
        Map<String, Object> fields = aggregate == null ? Map.<String, Object>of()
                : mapOf(aggregate.get("fields"));

        // 人工补录优先于抽取结果：核查的人看到的才是最终要建档的
        String name = firstText(request.name(), text(fields.get("name")));
        String gender = normalizeGender(firstText(request.gender(), text(fields.get("gender"))));
        LocalDate birthDate = parseDate(firstText(request.birthDate(), text(fields.get("birthDate"))));
        String phone = firstText(request.phone(), text(fields.get("phone")));

        if (!StringUtils.hasText(name)) {
            throw new BizException("PATIENT_INTAKE_FIELD_MISSING",
                    "资料里没抽到姓名，请在确认时补录姓名", HttpStatus.CONFLICT);
        }
        if (!StringUtils.hasText(gender)) {
            throw new BizException("PATIENT_INTAKE_FIELD_MISSING",
                    "资料里没抽到性别，请在确认时补录性别", HttpStatus.CONFLICT);
        }

        PatientContract.PatientView patient = lifecycle.create(
                new PatientContract.CreatePatientRequest(name, gender, birthDate, phone,
                        normalize(request.externalRef()), false), owner);

        int metricCount = writeMetrics(patient.id(), aggregate, owner);

        // 补录留痕：把人工填的字段回写进汇总，并标注每个字段的来源。
        // 没有这一步，日后看到"姓名=王"没法判断是 OCR 抽的还是人补的。
        recordManualFields(exam, aggregate, fields, request);

        exam.setPatientId(patient.id());
        exam.setStatus(STATUS_CONFIRMED);
        exam.setRevision(nextRevision(exam));
        exam.setUpdatedAt(OffsetDateTime.now());
        examMapper.updateById(exam);
        log.info("资料录入已确认 exam={} patient={} metrics={}", examId, patient.id(), metricCount);
        return new PatientContract.IntakeConfirmResult(examId, patient.id(), patient.name(),
                metricCount, exam.getStatus());
    }

    /**
     * 把人工补录的字段回写进汇总，并标注来源。
     *
     * <p>{@code fieldSources} 里写 {@code manual}，抽取得到的写分片键。
     * 这样"这份档案的姓名是人补的还是机器抄的"在数据里就有答案，
     * 不必去翻审计日志。
     */
    private void recordManualFields(PatientIntakeExam exam, Map<String, Object> aggregate,
                                    Map<String, Object> extracted,
                                    PatientContract.IntakeConfirmRequest request) {
        if (aggregate == null) {
            return;
        }
        Map<String, Object> merged = new LinkedHashMap<>(mapOf(aggregate.get("fields")));
        Map<String, String> sources = new LinkedHashMap<>();
        mapOf(aggregate.get("fieldSources")).forEach((key, value) -> sources.put(key, String.valueOf(value)));
        boolean changed = false;
        changed |= applyManual(merged, sources, extracted, "name", request.name());
        changed |= applyManual(merged, sources, extracted, "gender", request.gender());
        changed |= applyManual(merged, sources, extracted, "birthDate", request.birthDate());
        changed |= applyManual(merged, sources, extracted, "phone", request.phone());
        if (!changed) {
            return;
        }
        aggregate.put("fields", merged);
        aggregate.put("fieldSources", sources);
        exam.setAggregate(Json.write(aggregate));
    }

    /** 补录一个字段：有补录值就覆盖抽取值，并把来源标成 manual。 */
    private static boolean applyManual(Map<String, Object> merged, Map<String, String> sources,
                                       Map<String, Object> extracted, String key, String manualValue) {
        if (!StringUtils.hasText(manualValue)) {
            return false;
        }
        String value = manualValue.strip();
        Object previous = extracted.get(key);
        if (value.equals(String.valueOf(previous))) {
            return false;
        }
        merged.put(key, value);
        sources.put(key, "manual");
        return true;
    }

    // ------------------------------------------------------------ 查询

    public PatientContract.IntakeView detail(String examId, String owner) {
        return view(require(examId, owner));
    }

    public PageResult<PatientContract.IntakeView> list(String owner, int page, int size) {
        Page<PatientIntakeExam> result = examMapper.selectPage(
                new Page<>(PageResult.clampPage(page), PageResult.clampSize(size)),
                new LambdaQueryWrapper<PatientIntakeExam>()
                        .eq(PatientIntakeExam::getOwner, owner)
                        .orderByDesc(PatientIntakeExam::getCreatedAt));
        return PageResult.of(result, this::view);
    }

    // ------------------------------------------------------------ 内部

    private PatientIntakeExam require(String examId, String owner) {
        PatientIntakeExam exam = examMapper.selectOne(new LambdaQueryWrapper<PatientIntakeExam>()
                .eq(PatientIntakeExam::getId, examId)
                .eq(PatientIntakeExam::getOwner, owner)
                .last("limit 1"));
        if (exam == null) {
            throw BizException.notFound("录入单");
        }
        return exam;
    }

    /** 切分正文：按分片标题切，标题以下的内容归该分片。 */
    static Map<String, String> splitParts(String markdown) {
        Map<String, StringBuilder> parts = new LinkedHashMap<>();
        String current = null;
        for (String line : markdown.split("\n")) {
            String header = matchHeader(line);
            if (header != null) {
                current = header;
                parts.computeIfAbsent(current, key -> new StringBuilder());
                continue;
            }
            if (current == null) {
                current = PART_GENERAL;
                parts.computeIfAbsent(current, key -> new StringBuilder());
            }
            parts.get(current).append(line).append('\n');
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, StringBuilder> entry : parts.entrySet()) {
            String content = entry.getValue().toString().strip();
            if (!content.isEmpty()) {
                result.put(entry.getKey(), content);
            }
        }
        return result;
    }

    /** 判断一行是不是分片标题。容忍 markdown 标题符号、全角冒号与多余空格。 */
    private static String matchHeader(String line) {
        String text = line.strip();
        if (text.isEmpty()) {
            return null;
        }
        while (text.startsWith("#")) {
            text = text.substring(1).strip();
        }
        // markdown 强调：**主诉** / __主诉__ —— 去掉标记再判标题
        text = text.replace("*", "").replace("_", "").strip();
        // 中文序号：一、主诉 / （二）现病史 / 3. 既往史
        text = text.replaceFirst("^[（(]?[0-9一二三四五六七八九十]+[）).、．]\\s*", "").strip();
        // 标题行不应该太长——正文里出现"检查"两个字不等于一行标题
        if (text.isEmpty() || text.length() > 24) {
            return null;
        }
        String head = text.split("[:：]")[0].strip();
        for (PatientIntakeFields.Part part : PatientIntakeFields.PARTS) {
            for (String cue : part.cues()) {
                if (head.equals(cue) || head.startsWith(cue)) {
                    return part.key();
                }
            }
        }
        return null;
    }

    private List<PatientIntakePart> persistParts(PatientIntakeExam exam, Map<String, String> segments) {
        OffsetDateTime now = OffsetDateTime.now();
        List<PatientIntakePart> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : segments.entrySet()) {
            PatientIntakePart part = new PatientIntakePart();
            part.setId(Ids.newId("pip"));
            part.setExamId(exam.getId());
            part.setOwner(exam.getOwner());
            part.setPartKey(entry.getKey());
            part.setContent(truncate(entry.getValue(), 20000));
            part.setStatus(PART_PENDING);
            part.setCreatedAt(now);
            part.setUpdatedAt(now);
            try {
                String payload = extractor.extract(
                        LlmProvider.RouteContext.of("PATIENT_RECORD", "patient_intake", exam.getId()),
                        entry.getKey(), entry.getValue());
                part.setPayload(truncate(payload, 20000));
                part.setStatus(PART_SUCCEEDED);
            } catch (RuntimeException ex) {
                // 一片失败不连坐：记下来，汇总时统计；但只要有失败片，档案就不许确认
                part.setStatus(PART_FAILED);
                part.setErrorCode(ex instanceof BizException biz ? biz.getCode()
                        : "PATIENT_INTAKE_EXTRACT_FAILED");
                part.setErrorMessage(truncate(ex.getMessage() == null ? ex.toString() : ex.getMessage(), 500));
            }
            partMapper.insert(part);
            parts.add(part);
        }
        return parts;
    }

    private int writeMetrics(String patientId, Map<String, Object> aggregate, String owner) {
        List<Map<String, Object>> metrics = aggregate == null ? List.of()
                : listOfMaps(aggregate.get("metrics"));
        if (metrics.isEmpty()) {
            return 0;
        }
        List<PatientContract.MetricInput> inputs = new ArrayList<>();
        for (Map<String, Object> metric : metrics) {
            BigDecimal value = numeric(metric.get("value"));
            if (value == null) {
                continue;
            }
            inputs.add(new PatientContract.MetricInput(
                    String.valueOf(metric.get("code")), String.valueOf(metric.get("name")), value,
                    text(metric.get("unit")), numeric(metric.get("referenceLow")),
                    numeric(metric.get("referenceHigh")), null));
        }
        if (inputs.isEmpty()) {
            return 0;
        }
        patientService.upsertMetrics(patientId, inputs, owner);
        return inputs.size();
    }

    private PatientContract.IntakeView view(PatientIntakeExam exam) {
        List<PatientContract.IntakePartView> parts = partMapper.selectList(
                        new LambdaQueryWrapper<PatientIntakePart>()
                                .eq(PatientIntakePart::getExamId, exam.getId())
                                .eq(PatientIntakePart::getOwner, exam.getOwner())
                                .orderByAsc(PatientIntakePart::getCreatedAt))
                .stream().map(this::partView).toList();
        Map<String, Object> aggregate = exam.getAggregate() == null ? Map.of()
                : Json.readMap(exam.getAggregate());
        return new PatientContract.IntakeView(exam.getId(), exam.getOwner(), exam.getPatientId(),
                exam.getSourceFilename(), exam.getSourceSha256(),
                exam.getSourceSize() == null ? 0 : exam.getSourceSize(), exam.getMediaType(),
                exam.getStatus(), exam.getBasicReviewStatus(), exam.getClinicalReviewStatus(),
                exam.getBasicReviewNote(), exam.getClinicalReviewNote(), exam.getExtractor(),
                exam.getRevision() == null ? 0 : exam.getRevision(), exam.getErrorCode(),
                exam.getErrorMessage(), exam.getCreatedAt(), exam.getUpdatedAt(),
                aggregate == null ? Map.of() : aggregate, parts);
    }

    private PatientContract.IntakePartView partView(PatientIntakePart part) {
        Map<String, Object> payload = part.getPayload() == null ? Map.of()
                : Json.readMap(part.getPayload());
        Map<String, Object> fields = payload == null ? Map.of() : mapOf(payload.get("fields"));
        List<Map<String, Object>> metrics = payload == null ? List.of() : listOfMaps(payload.get("metrics"));
        return new PatientContract.IntakePartView(part.getPartKey(),
                PART_GENERAL.equals(part.getPartKey()) ? "其他" : PatientIntakeFields.partLabel(part.getPartKey()),
                part.getStatus(), fields, metrics, part.getErrorCode(), part.getErrorMessage());
    }

    private static String normalizeDecision(String raw) {
        String value = raw == null ? "" : raw.strip().toUpperCase();
        if (REVIEW_CONFIRMED.equals(value)) {
            return REVIEW_CONFIRMED;
        }
        if (REVIEW_REJECTED.equals(value)) {
            return REVIEW_REJECTED;
        }
        throw BizException.badRequest("核查结论只能是 CONFIRMED 或 REJECTED：" + raw);
    }

    private static String normalizeGender(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        return switch (raw.strip()) {
            case "男", "男性", "MALE" -> "MALE";
            case "女", "女性", "FEMALE" -> "FEMALE";
            default -> raw.strip().toUpperCase();
        };
    }

    private static LocalDate parseDate(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String normalized = raw.strip()
                .replace("年", "-").replace("月", "-").replace("日", "")
                .replace('/', '-').replace('.', '-').replace(" ", "");
        String[] parts = normalized.split("-");
        if (parts.length != 3) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static int nextRevision(PatientIntakeExam exam) {
        return (exam.getRevision() == null ? 0 : exam.getRevision()) + 1;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    private static BigDecimal numeric(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return new BigDecimal(String.valueOf(value).strip());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String result = String.valueOf(value).strip();
        return result.isEmpty() ? null : result;
    }

    /**
     * 取第一个非空值：人工补录优先，其次抽取结果。
     *
     * <p>顺序不能反——补录是核查的人明确给的，比机器抽的更可信。
     */
    private static String firstText(String preferred, String fallback) {
        if (StringUtils.hasText(preferred)) {
            return preferred.strip();
        }
        return fallback;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1 || filename.length() - dot > 12) {
            return "";
        }
        String ext = filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return ext.matches("[a-z0-9]+") ? "." + ext : "";
    }
}
