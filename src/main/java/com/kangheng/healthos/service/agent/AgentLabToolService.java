package com.kangheng.healthos.service.agent;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.kangheng.healthos.contract.agent.AgentCollaborationContract.AgentScope;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract.AgentTool;
import com.kangheng.healthos.contract.agent.AgentLabContract;
import com.kangheng.healthos.contract.agent.AgentLabContract.ConfiguredTool;
import com.kangheng.healthos.mapper.care.CareDocumentMapper;
import com.kangheng.healthos.mapper.patient.PatientMapper;
import com.kangheng.healthos.mapper.patient.PatientMemoryJobMapper;
import com.kangheng.healthos.mapper.patient.PatientMemoryStateMapper;
import com.kangheng.healthos.model.care.CareDocument;
import com.kangheng.healthos.model.patient.Patient;
import com.kangheng.healthos.model.patient.PatientMemoryJob;
import com.kangheng.healthos.model.patient.PatientMemoryState;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 进程内工具目录与 6 个只读工具的真实读取。
 *
 * <p>这里等价于原工程那个 STDIO MCP Server：{@code tools/list} 由 {@link #discover}
 * 从目录枚举出工具，{@code tools/call} 由 {@link #execute} 真正查库。没有子进程，
 * 但"发现"和"调用"都是实打实对进程内目录/业务数据做的，不是写死的假响应。
 *
 * <p><b>owner 必须参与每一次查询</b>：6 个工具读的全是患者域数据，缺了 owner 过滤
 * 就等于开了一个按 patientId 遍历他人档案的口子。患者 ID 由上层按登录态锁定后
 * 传进来（参数里禁止携带 patientId/owner），本层只认这两个入参，不再相信请求体。
 *
 * <p><b>输出只放业务摘要</b>：报告/计划只回 ID、状态、版本；任务不回 error 原文。
 * 整份档案正文、失败原因里可能夹带的 SQL/连接串/身份证/手机号都不进工具输出，
 * 也就不会顺着 outputHash 与回执 detail 被持久化下来。
 */
@Service
public class AgentLabToolService {

    private static final String KIND_REPORT = "report";
    private static final String KIND_PLAN = "plan";
    private static final List<String> REPORT_ACTIVE_STATUSES = List.of("SIGNED", "ACTIVE");
    private static final List<String> SIGNED_STATUSES = List.of("SIGNED", "ACTIVE", "SUPERSEDED");

    private final PatientMemoryStateMapper stateMapper;
    private final PatientMemoryJobMapper jobMapper;
    private final CareDocumentMapper careDocumentMapper;
    private final PatientMapper patientMapper;

    public AgentLabToolService(PatientMemoryStateMapper stateMapper,
                               PatientMemoryJobMapper jobMapper,
                               CareDocumentMapper careDocumentMapper,
                               PatientMapper patientMapper) {
        this.stateMapper = stateMapper;
        this.jobMapper = jobMapper;
        this.careDocumentMapper = careDocumentMapper;
        this.patientMapper = patientMapper;
    }

    /**
     * {@code tools/list}：从进程内目录枚举出本次授权范围能看到的工具。
     *
     * <p>它是"服务端实际暴露了什么"，与 {@code AgentLabContract.authorizedToolWires}
     * 那张独立授权表不是一份代码——两边对不上就是目录/授权漂移，握手期会被判
     * {@code AGENT_DISCOVERY_MISMATCH}。
     */
    public Set<String> discover(Set<AgentScope> scopes) {
        Set<String> wires = new LinkedHashSet<>();
        for (AgentScope scope : scopes) {
            wires.add(scope.wire());
        }
        Set<String> result = new LinkedHashSet<>();
        for (ConfiguredTool tool : AgentLabContract.configuredTools()) {
            if (wires.contains(tool.scope())) {
                result.add(tool.name());
            }
        }
        return result;
    }

    /** {@code tools/call}：真实调用 6 个只读工具之一，返回结构化 {@code structuredContent}。 */
    public Map<String, Object> execute(AgentTool tool, String owner, String patientId,
                                       Map<String, Object> arguments) {
        // arguments 已在 AgentLabService 做过白名单过滤，这里只剩可选的 resourceId。
        // 6 个工具一律按服务端锁定的 owner + patientId 查，刻意不读取任何请求侧参数——
        // 这样即便将来有人把 patientId/owner 塞回 arguments，也绝无可能借参数越权。
        if (tool == null) {
            // 不该发生；返回空让上层统一判 MCP_OUTPUT_INVALID，而不是在这里抛异常。
            return Map.of();
        }
        return switch (tool) {
            case READ_PATIENT_MEMORY -> readPatientMemory(owner, patientId);
            case READ_PATIENT_REPORT -> readPatientReport(owner, patientId);
            case GET_PIPELINE_STATUS -> getPipelineStatus(owner, patientId);
            case GET_PLAN_PROGRESS -> getPlanProgress(owner, patientId);
            case GET_PATIENT_STATISTICS -> getPatientStatistics(owner);
            case GET_REPORT_STATISTICS -> getReportStatistics(owner);
        };
    }

    // ------------------------------------------------------------ 6 个工具

    /** {patientId, status: available|not_initialized, memoryId?, version?} */
    private Map<String, Object> readPatientMemory(String owner, String patientId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("patientId", patientId);
        // patient_memory_state 是"当前看哪一版"的指针；查不到即尚未初始化长效记忆。
        PatientMemoryState state = stateMapper.selectOne(new LambdaQueryWrapper<PatientMemoryState>()
                .eq(PatientMemoryState::getPatientId, patientId)
                .eq(PatientMemoryState::getOwner, owner)
                .last("limit 1"));
        if (state != null && StringUtils.hasText(state.getCurrentMemoryId())) {
            out.put("status", "available");
            out.put("memoryId", state.getCurrentMemoryId());
            out.put("version", state.getVersion() == null ? 0 : state.getVersion());
        } else {
            out.put("status", "not_initialized");
        }
        return out;
    }

    /** {patientId, report?, sources[], sampledAt} */
    private Map<String, Object> readPatientReport(String owner, String patientId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("patientId", patientId);
        List<CareDocument> docs = careDocumentMapper.selectList(new LambdaQueryWrapper<CareDocument>()
                .eq(CareDocument::getOwner, owner)
                .eq(CareDocument::getPatientId, patientId)
                .eq(CareDocument::getKind, KIND_REPORT)
                .in(CareDocument::getStatus, REPORT_ACTIVE_STATUSES)
                .orderByDesc(CareDocument::getCreatedAt)
                .last("limit 20"));
        if (docs == null) {
            docs = List.of();
        }
        List<Map<String, Object>> sources = new ArrayList<>();
        for (CareDocument doc : docs) {
            sources.add(source(doc.getId(), doc.getRevision()));
        }
        if (!docs.isEmpty()) {
            CareDocument current = docs.get(0);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("id", nullToEmpty(current.getId()));
            report.put("status", nullToEmpty(current.getStatus()));
            report.put("revision", current.getRevision() == null ? 0 : current.getRevision());
            if (current.getSignedRevision() != null) {
                report.put("signedRevision", current.getSignedRevision());
            }
            // 只回元数据，不把 sections 正文塞进去——那是患者档案原文。
            out.put("report", report);
        }
        out.put("sources", sources);
        out.put("sampledAt", now());
        return out;
    }

    /** {patientId, sampledAt, jobs[], statusCounts} */
    private Map<String, Object> getPipelineStatus(String owner, String patientId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("patientId", patientId);
        List<PatientMemoryJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<PatientMemoryJob>()
                .eq(PatientMemoryJob::getOwner, owner)
                .eq(PatientMemoryJob::getPatientId, patientId)
                .orderByDesc(PatientMemoryJob::getCreatedAt)
                .last("limit 50"));
        if (jobs == null) {
            jobs = List.of();
        }
        List<Map<String, Object>> jobViews = new ArrayList<>();
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        for (PatientMemoryJob job : jobs) {
            String status = nullToEmpty(job.getStatus());
            statusCounts.merge(status.isEmpty() ? "UNKNOWN" : status, 1, Integer::sum);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", nullToEmpty(job.getId()));
            view.put("status", status);
            view.put("kind", nullToEmpty(job.getKind()));
            view.put("createdAt", job.getCreatedAt() == null ? "" : String.valueOf(job.getCreatedAt()));
            // 不带 error：失败原因可能含 SQL/连接串，不进工具输出。
            jobViews.add(view);
        }
        out.put("jobs", jobViews);
        out.put("statusCounts", statusCounts);
        out.put("sampledAt", now());
        return out;
    }

    /** {patientId, plan?, sources[], sampledAt} */
    private Map<String, Object> getPlanProgress(String owner, String patientId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("patientId", patientId);
        List<CareDocument> plans = careDocumentMapper.selectList(new LambdaQueryWrapper<CareDocument>()
                .eq(CareDocument::getOwner, owner)
                .eq(CareDocument::getPatientId, patientId)
                .eq(CareDocument::getKind, KIND_PLAN)
                .eq(CareDocument::getStatus, "ACTIVE")
                .orderByDesc(CareDocument::getCreatedAt)
                .last("limit 20"));
        if (plans == null) {
            plans = List.of();
        }
        List<Map<String, Object>> sources = new ArrayList<>();
        for (CareDocument doc : plans) {
            sources.add(source(doc.getId(), doc.getRevision()));
        }
        if (!plans.isEmpty()) {
            CareDocument active = plans.get(0);
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("documentId", nullToEmpty(active.getId()));
            plan.put("revision", active.getRevision() == null ? 0 : active.getRevision());
            plan.put("status", nullToEmpty(active.getStatus()));
            if (active.getStartDate() != null) {
                plan.put("startDate", active.getStartDate());
            }
            out.put("plan", plan);
        }
        out.put("sources", sources);
        out.put("sampledAt", now());
        return out;
    }

    /** {scope, totalPatients, createdToday, sampledAt, timezone} */
    private Map<String, Object> getPatientStatistics(String owner) {
        Map<String, Object> out = new LinkedHashMap<>();
        // scope 用中性标签而不是账号名：用户名可能是邮箱，不该出现在工具输出里。
        out.put("scope", "owner");
        Long total = patientMapper.selectCount(new LambdaQueryWrapper<Patient>()
                .eq(Patient::getCreatedBy, owner));
        out.put("totalPatients", total == null ? 0L : total);
        Long createdToday = patientMapper.selectCount(new LambdaQueryWrapper<Patient>()
                .eq(Patient::getCreatedBy, owner)
                .ge(Patient::getCreatedAt, AgentLabContract.startOfToday()));
        out.put("createdToday", createdToday == null ? 0L : createdToday);
        out.put("sampledAt", now());
        out.put("timezone", AgentLabContract.TIMEZONE);
        return out;
    }

    /** {scope, reportDocuments, signedReports, byStatus{}, sampledAt, timezone} */
    private Map<String, Object> getReportStatistics(String owner) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", "owner");
        // 只取 status 一列做分组，不拉整份文档正文。
        List<CareDocument> reports = careDocumentMapper.selectList(new LambdaQueryWrapper<CareDocument>()
                .select(CareDocument::getStatus)
                .eq(CareDocument::getOwner, owner)
                .eq(CareDocument::getKind, KIND_REPORT));
        if (reports == null) {
            reports = List.of();
        }
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        long reportDocuments = 0L;
        long signedReports = 0L;
        for (CareDocument doc : reports) {
            String status = nullToEmpty(doc.getStatus());
            reportDocuments++;
            byStatus.merge(status.isEmpty() ? "UNKNOWN" : status, 1, Integer::sum);
            // DRAFT 是草稿，不算正式报告；SIGNED/ACTIVE/SUPERSEDED 都经过签发。
            if (SIGNED_STATUSES.contains(status)) {
                signedReports++;
            }
        }
        out.put("reportDocuments", reportDocuments);
        out.put("signedReports", signedReports);
        out.put("byStatus", byStatus);
        out.put("sampledAt", now());
        out.put("timezone", AgentLabContract.TIMEZONE);
        return out;
    }

    // ------------------------------------------------------------ 小工具

    /**
     * 工具参数白名单：只保留可选的 resourceId。
     *
     * <p>{@code patientId} / {@code owner} 以及其它键全部丢弃。患者由服务端按登录态锁定后
     * 作为独立入参传给工具，绝不从参数里取——"工具参数禁止携带 patientId/owner"这条约束
     * 在这里落地，而不是靠调用方自觉。
     *
     * <p>进程内与子进程两条链路共用这一份：子进程回调时同样要过一遍，
     * 否则"换个传输层就多一条不过滤的路径"。
     */
    public static Map<String, Object> sanitizeArguments(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw == null) {
            return result;
        }
        Object resourceId = raw.get("resourceId");
        if (resourceId == null) {
            resourceId = raw.get("resource_id");
        }
        if (resourceId instanceof String text && StringUtils.hasText(text)) {
            result.put("resourceId", text);
        }
        return result;
    }

    private Map<String, Object> source(String id, Integer version) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("id", nullToEmpty(id));
        ref.put("version", version == null ? 0 : version);
        return ref;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String now() {
        return String.valueOf(OffsetDateTime.now());
    }
}
