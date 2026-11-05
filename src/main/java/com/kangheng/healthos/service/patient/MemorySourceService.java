package com.kangheng.healthos.service.patient;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.contract.care.CareContract;
import com.kangheng.healthos.contract.patient.PatientMemoryContract;
import com.kangheng.healthos.mapper.care.CareCheckinMapper;
import com.kangheng.healthos.mapper.care.CareDocumentMapper;
import com.kangheng.healthos.mapper.care.CarePatientStateMapper;
import com.kangheng.healthos.mapper.care.CareRevisionMapper;
import com.kangheng.healthos.mapper.care.CareSignatureMapper;
import com.kangheng.healthos.mapper.patient.PatientFollowupMapper;
import com.kangheng.healthos.mapper.patient.PatientMetricMapper;
import com.kangheng.healthos.mapper.patient.PatientRevisionMapper;
import com.kangheng.healthos.model.care.CareCheckin;
import com.kangheng.healthos.model.care.CareDocument;
import com.kangheng.healthos.model.care.CarePatientState;
import com.kangheng.healthos.model.care.CareRevision;
import com.kangheng.healthos.model.care.CareSignature;
import com.kangheng.healthos.model.patient.PatientFollowup;
import com.kangheng.healthos.model.patient.PatientMetric;
import com.kangheng.healthos.model.patient.PatientRevision;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 记忆的**事实采集**：把散落在各处的"已发布事实"冻结成一份带出处的快照。
 *
 * <p>对齐原工程 {@code memory_service.capture_sources / capture_care_sources} 的核心纪律：
 * <b>只从不可变发布修订取事实</b>，不从当前可变状态现取后假装它是历史。
 * 每一条来源都记 {@code contentHash}，版本里的 {@code sourceHash} 是这些来源的摘要，
 * 因此可以回答"这一版记忆到底依据了什么、依据有没有被改过"。
 *
 * <p>一处**诚实标注的偏差**：原工程的异常指标来自已核验的体检修订快照，
 * 本工程的 {@code patient_metric} 是当前可变表，没有对应的不可变修订。
 * 因此指标以 {@code patient_metric_snapshot} 单独登记，并在来源与 Markdown 里明确写
 * "当前状态采样，非已发布修订"——采样即冻结，但绝不冒充已核验历史。
 */
@Service
public class MemorySourceService {

    public static final String SCHEMA_VERSION = "memory-1";
    public static final String GENERATION_MODE = "programmatic_facts";

    /** 与主工程 memory_jobs.CATEGORIES 对齐的四类随访。 */
    public static final Map<String, String> CATEGORIES = Map.of(
            "diet", "饮食打卡反馈",
            "symptoms", "躯体症状感受",
            "exercise", "运动执行记录",
            "lifestyle", "综合生活方式");

    private static final String ERROR_INCOMPLETE = "MEMORY_SOURCE_INCOMPLETE";

    private final PatientRevisionMapper patientRevisionMapper;
    private final PatientMetricMapper patientMetricMapper;
    private final PatientFollowupMapper followupMapper;
    private final CarePatientStateMapper careStateMapper;
    private final CareRevisionMapper careRevisionMapper;
    private final CareSignatureMapper careSignatureMapper;
    private final CareCheckinMapper careCheckinMapper;
    private final CareDocumentMapper careDocumentMapper;

    public MemorySourceService(PatientRevisionMapper patientRevisionMapper,
                               PatientMetricMapper patientMetricMapper,
                               PatientFollowupMapper followupMapper,
                               CarePatientStateMapper careStateMapper,
                               CareRevisionMapper careRevisionMapper,
                               CareSignatureMapper careSignatureMapper,
                               CareCheckinMapper careCheckinMapper,
                               CareDocumentMapper careDocumentMapper) {
        this.patientRevisionMapper = patientRevisionMapper;
        this.patientMetricMapper = patientMetricMapper;
        this.followupMapper = followupMapper;
        this.careStateMapper = careStateMapper;
        this.careRevisionMapper = careRevisionMapper;
        this.careSignatureMapper = careSignatureMapper;
        this.careCheckinMapper = careCheckinMapper;
        this.careDocumentMapper = careDocumentMapper;
    }

    /** 一次采样的结果：事实快照 + 逐条来源。 */
    public record Capture(Map<String, Object> data, List<Map<String, Object>> sources) {
    }

    public Capture capture(String owner, String patientId, OffsetDateTime sampledAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        data.put("patientId", patientId);
        data.put("schemaVersion", SCHEMA_VERSION);

        data.put("baseline", baseline(owner, patientId, sources));
        data.put("metrics", metrics(owner, patientId, sources));
        CarePatientState careState = careStateOf(owner, patientId);
        data.put("report", careState == null ? null : report(owner, careState, sources));
        data.put("plan", careState == null ? null : plan(owner, careState, sources));
        data.put("followups", followups(owner, patientId, sources));
        for (Map<String, Object> source : sources) {
            source.put("sampledAt", sampledAt.toString());
        }
        return new Capture(data, sources);
    }

    // ------------------------------------------------------------ 基线：已落库的档案修订

    private Map<String, Object> baseline(String owner, String patientId,
                                         List<Map<String, Object>> sources) {
        PatientRevision latest = patientRevisionMapper.selectList(
                        new LambdaQueryWrapper<PatientRevision>()
                                .eq(PatientRevision::getOwner, owner)
                                .eq(PatientRevision::getPatientId, patientId)
                                .orderByDesc(PatientRevision::getRevision)
                                .last("limit 1"))
                .stream().findFirst().orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        if (latest == null) {
            result.put("available", false);
            result.put("patientId", patientId);
            return result;
        }
        Map<String, Object> snapshot = Json.readMap(latest.getSnapshot());
        for (String key : List.of("name", "gender", "birthDate", "externalRef", "status", "riskLevel")) {
            result.put(key, snapshot.get(key));
        }
        result.put("phoneMasked", mask(String.valueOf(snapshot.getOrDefault("phone", ""))));
        result.put("available", true);
        result.put("revision", latest.getRevision());
        result.put("snapshotSha256", latest.getSha256());
        sources.add(source("patient_revision", latest.getId(), latest.getRevision(),
                latest.getSnapshot(), "已落库的档案修订快照"));
        return result;
    }

    /** 身份证/手机号一类字段只留末四位，记忆导出件不该变成一份完整的个人信息副本。 */
    private String mask(String raw) {
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            return null;
        }
        int keep = Math.min(4, raw.length());
        return "*".repeat(Math.max(4, raw.length() - keep)) + raw.substring(raw.length() - keep);
    }

    // ------------------------------------------------------------ 指标：当前状态采样（明确标注）

    private List<Map<String, Object>> metrics(String owner, String patientId,
                                              List<Map<String, Object>> sources) {
        List<PatientMetric> rows = patientMetricMapper.selectList(
                new LambdaQueryWrapper<PatientMetric>()
                        .eq(PatientMetric::getOwner, owner)
                        .eq(PatientMetric::getPatientId, patientId)
                        .orderByAsc(PatientMetric::getCode));
        List<Map<String, Object>> items = new ArrayList<>();
        for (PatientMetric row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", row.getCode());
            item.put("name", row.getName());
            item.put("value", row.getValue());
            item.put("unit", row.getUnit());
            item.put("referenceLow", row.getReferenceLow());
            item.put("referenceHigh", row.getReferenceHigh());
            item.put("status", row.getStatus());
            item.put("measuredAt", row.getMeasuredAt() == null ? null : row.getMeasuredAt().toString());
            items.add(item);
        }
        if (!items.isEmpty()) {
            // 不是不可变修订，不能和 patient_revision 混成一类
            sources.add(source("patient_metric_snapshot", patientId, null, items,
                    "当前状态采样，非已发布修订；采样即冻结，仅代表本次采样时刻"));
        }
        return items;
    }

    // ------------------------------------------------------------ 照护：已签发报告 / 已生效计划

    private CarePatientState careStateOf(String owner, String patientId) {
        CarePatientState state = careStateMapper.selectById(patientId);
        if (state == null) {
            return null;
        }
        if (!owner.equals(state.getOwner())) {
            // 指针归属异常不静默当成"没有报告"
            throw new BizException(ERROR_INCOMPLETE, "照护发布指针归属异常", HttpStatus.CONFLICT);
        }
        return state;
    }

    private Map<String, Object> report(String owner, CarePatientState state,
                                       List<Map<String, Object>> sources) {
        String documentId = state.getActiveReportId();
        Integer revisionNo = state.getActiveReportRevision();
        if (!StringUtils.hasText(documentId) || revisionNo == null) {
            return null;
        }
        Map<String, Object> frozen = frozenRevision(documentId, revisionNo, "SIGNED", "当前签发报告");
        CareSignature signature = careSignatureMapper.selectOne(
                new LambdaQueryWrapper<CareSignature>()
                        .eq(CareSignature::getDocumentId, documentId)
                        .eq(CareSignature::getSignedRevision, revisionNo)
                        .eq(CareSignature::getOwner, owner)
                        .last("limit 1"));
        if (signature == null) {
            // 源工程同样在这里拒绝：没有签署记录的"已签发报告"不是可引用的依据
            throw new BizException(ERROR_INCOMPLETE, "当前签发报告缺少匹配的签署记录", HttpStatus.CONFLICT);
        }
        sources.add(source("care_report", documentId, revisionNo, frozen, "已签发的不可变发布修订"));
        sources.add(source("care_signature", signature.getId(), revisionNo,
                Map.of("signer", String.valueOf(signature.getSigner()),
                        "kind", String.valueOf(signature.getKind()),
                        "contentHash", String.valueOf(signature.getContentHash())),
                "签发记录"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documentId", documentId);
        result.put("revision", revisionNo);
        result.put("signer", signature.getSigner());
        result.put("signedAt", signature.getSignedAt() == null ? null : signature.getSignedAt().toString());
        result.put("signatureKind", signature.getKind());
        result.put("sections", sections(frozen, CareContract.REPORT_KEYS));
        return result;
    }

    private Map<String, Object> plan(String owner, CarePatientState state,
                                     List<Map<String, Object>> sources) {
        String documentId = state.getActivePlanId();
        Integer revisionNo = state.getActivePlanRevision();
        if (!StringUtils.hasText(documentId) || revisionNo == null) {
            return null;
        }
        Map<String, Object> frozen = frozenRevision(documentId, revisionNo, "ACTIVE", "当前生效计划");
        sources.add(source("care_plan", documentId, revisionNo, frozen, "已确认生效的不可变发布修订"));

        List<CareCheckin> checkins = careCheckinMapper.selectList(
                new LambdaQueryWrapper<CareCheckin>()
                        .eq(CareCheckin::getDocumentId, documentId)
                        .eq(CareCheckin::getOwner, owner));
        Map<String, CareCheckin> byTask = new LinkedHashMap<>();
        for (CareCheckin checkin : checkins) {
            byTask.put(checkin.getTaskId(), checkin);
        }
        Object tasksRaw = frozen.get("tasks");
        List<Map<String, Object>> tasks = new ArrayList<>();
        int completed = 0;
        if (tasksRaw instanceof List<?> list) {
            for (Object raw : list) {
                if (!(raw instanceof Map<?, ?> task)) {
                    continue;
                }
                String taskId = String.valueOf(task.get("taskId"));
                CareCheckin checkin = byTask.get(taskId);
                boolean done = checkin != null && Boolean.TRUE.equals(checkin.getCompleted());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("taskId", taskId);
                item.put("title", task.get("title"));
                item.put("content", task.get("content"));
                item.put("completed", done);
                item.put("checkinVersion", checkin == null ? 0 : checkin.getVersion());
                tasks.add(item);
                if (done) {
                    completed++;
                }
            }
        }
        for (CareCheckin checkin : checkins) {
            sources.add(source("care_checkin", checkin.getId(), checkin.getVersion(),
                    Map.of("taskId", String.valueOf(checkin.getTaskId()),
                            "completed", String.valueOf(checkin.getCompleted())),
                    "打卡当前状态采样"));
        }

        CareDocument document = careDocumentMapper.selectById(documentId);
        boolean matches = document != null
                && document.getSourceReportId() != null
                && document.getSourceReportId().equals(state.getActiveReportId())
                && document.getSourceReportRevision() != null
                && document.getSourceReportRevision().equals(state.getActiveReportRevision());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documentId", documentId);
        result.put("revision", revisionNo);
        result.put("startDate", document == null ? null : document.getStartDate());
        result.put("matchesCurrentReport", matches);
        result.put("sourceReportId", document == null ? null : document.getSourceReportId());
        result.put("totalTasks", tasks.size());
        result.put("completedTasks", completed);
        result.put("completionRate", tasks.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(completed * 100.0 / tasks.size()).setScale(1, java.math.RoundingMode.HALF_UP));
        result.put("tasks", tasks);
        return result;
    }

    private Map<String, Object> frozenRevision(String documentId, int revisionNo,
                                               String expectedStatus, String label) {
        CareRevision revision = careRevisionMapper.selectOne(
                new LambdaQueryWrapper<CareRevision>()
                        .eq(CareRevision::getDocumentId, documentId)
                        .eq(CareRevision::getRevision, revisionNo)
                        .last("limit 1"));
        if (revision == null) {
            throw new BizException(ERROR_INCOMPLETE,
                    label + "缺少不可变发布修订 r" + revisionNo, HttpStatus.CONFLICT);
        }
        Map<String, Object> frozen = Json.readMap(revision.getSnapshot());
        if (!expectedStatus.equals(frozen.get("status"))) {
            throw new BizException(ERROR_INCOMPLETE,
                    label + "的发布修订状态不是 " + expectedStatus + "，不能写入记忆", HttpStatus.CONFLICT);
        }
        return frozen;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sections(Map<String, Object> frozen, List<String> keys) {
        List<Map<String, Object>> result = new ArrayList<>();
        Object raw = frozen.get("sections");
        if (!(raw instanceof Map<?, ?> sectionMap)) {
            return result;
        }
        for (String key : keys) {
            Object sectionRaw = sectionMap.get(key);
            if (!(sectionRaw instanceof Map<?, ?> section)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", key);
            item.put("title", CareContract.SECTION_TITLES.getOrDefault(key, key));
            item.put("text", section.get("text") == null ? "" : section.get("text").toString());
            Object refs = section.get("metricRefs");
            item.put("metricRefs", refs instanceof List<?> list ? new ArrayList<>(list) : List.of());
            result.add(item);
        }
        return result;
    }

    // ------------------------------------------------------------ 随访：原文照搬

    private List<Map<String, Object>> followups(String owner, String patientId,
                                                List<Map<String, Object>> sources) {
        List<PatientFollowup> rows = followupMapper.selectList(
                new LambdaQueryWrapper<PatientFollowup>()
                        .eq(PatientFollowup::getOwner, owner)
                        .eq(PatientFollowup::getPatientId, patientId)
                        .orderByAsc(PatientFollowup::getRequestSequence));
        List<Map<String, Object>> result = new ArrayList<>();
        for (PatientFollowup row : rows) {
            List<PatientMemoryContract.FeedbackEntry> entries =
                    Json.readList(row.getEntries(), PatientMemoryContract.FeedbackEntry.class);
            List<Map<String, Object>> items = new ArrayList<>();
            for (PatientMemoryContract.FeedbackEntry entry : entries) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("category", entry.category());
                item.put("categoryLabel", CATEGORIES.getOrDefault(entry.category(), entry.category()));
                item.put("content", entry.content());
                items.add(item);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("feedbackId", row.getId());
            item.put("requestSequence", row.getRequestSequence());
            item.put("occurredAt", row.getOccurredAt().toString());
            item.put("createdAt", row.getCreatedAt().toString());
            item.put("actor", row.getActor());
            item.put("entries", items);
            result.add(item);
            sources.add(source("patient_followup", row.getId(), row.getRequestSequence(),
                    items, "患者自述原文，非医学核验事实"));
        }
        return result;
    }

    // ------------------------------------------------------------ 来源登记

    /**
     * @param payload 参与哈希的原文对象；用什么算 hash 必须是确定的（LinkedHashMap / List）
     */
    private Map<String, Object> source(String kind, String id, Integer revision,
                                       Object payload, String note) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", kind);
        item.put("id", id);
        item.put("revision", revision);
        item.put("contentHash", Ids.sha256Hex(Json.write(payload)));
        item.put("note", note);
        return item;
    }
}
