package com.kangheng.healthos.service.patient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.common.PageResult;
import com.kangheng.healthos.contract.patient.PatientMemoryContract;
import com.kangheng.healthos.mapper.patient.PatientMemoryJobMapper;
import com.kangheng.healthos.mapper.patient.PatientFollowupMapper;
import com.kangheng.healthos.model.patient.PatientFollowup;
import com.kangheng.healthos.model.patient.PatientMemoryJob;
import com.kangheng.healthos.model.patient.PatientMemoryVersion;
import com.kangheng.healthos.service.outbox.OutboxService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 随访与记忆刷新任务。
 *
 * <p>对齐原工程 {@code memory_jobs.submit / followup_page / control}。
 * 提交返回 202 + job，实际发布由 Outbox 驱动——这样随访不会卡在 HTTP 请求里，
 * 失败也有可重试的落点。
 *
 * <p>四个必须守住的约束：
 * <ol>
 *   <li><b>幂等</b>：{@code operationId} 由调用方生成，同患者重复提交返回同一个 job；
 *       同一 operationId 但内容变了必须报冲突，不能静默复用旧任务；</li>
 *   <li><b>原文保留</b>：随访内容不摘要、不改写、不过模型，控制字符之外一个字都不动；</li>
 *   <li><b>失败可见</b>：任务失败保留记录并写 error，不删除、不假装成功；</li>
 *   <li><b>归属隔离</b>：任务与随访逐表带 owner，越权 404。</li>
 * </ol>
 */
@Service
public class PatientMemoryJobService {

    private static final Logger log = LoggerFactory.getLogger(PatientMemoryJobService.class);

    public static final String EVENT_PUBLISH = "patient_memory_publish";

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final String KIND_FOLLOWUP = "followup_refresh";
    public static final String KIND_REFRESH = "explicit_refresh";

    private static final Pattern OPERATION_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final int MAX_ENTRIES = 4;
    private static final int MAX_CONTENT = 500;
    private static final OffsetDateTime MIN_OCCURRED =
            OffsetDateTime.of(1900, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final String ORIGIN = "business_ui";

    private final PatientMemoryJobMapper jobMapper;
    private final PatientFollowupMapper followupMapper;
    private final OutboxService outboxService;
    private final PatientMemoryService memoryService;

    public PatientMemoryJobService(PatientMemoryJobMapper jobMapper,
                                   PatientFollowupMapper followupMapper,
                                   OutboxService outboxService,
                                   PatientMemoryService memoryService) {
        this.jobMapper = jobMapper;
        this.followupMapper = followupMapper;
        this.outboxService = outboxService;
        this.memoryService = memoryService;
    }

    // ------------------------------------------------------------ 提交

    /** 提交随访并触发一次记忆刷新（202）。 */
    @Transactional
    public PatientMemoryContract.Accepted submitFollowup(String owner, String patientId,
                                                         PatientMemoryContract.FollowupRequest request) {
        validateCommon(owner, patientId, request.operationId(), request.expectedMemoryId());
        List<PatientMemoryContract.FeedbackEntry> entries = validateEntries(request.entries());
        OffsetDateTime occurredAt = validateOccurredAt(request.occurredAt());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", KIND_FOLLOWUP);
        body.put("occurredAt", occurredAt.toString());
        body.put("entries", entries);
        String requestHash = Ids.sha256Hex(Json.write(body));

        PatientMemoryJob existing = findByOperation(patientId, request.operationId());
        if (existing != null) {
            assertSameRequest(existing, requestHash);
            return new PatientMemoryContract.Accepted(toView(existing));
        }

        int sequence = nextSequence(owner, patientId);
        PatientFollowup followup = new PatientFollowup();
        followup.setId(Ids.newId("pfup"));
        followup.setOwner(owner);
        followup.setPatientId(patientId);
        followup.setOperationId(request.operationId());
        followup.setRequestSequence(sequence);
        followup.setOccurredAt(occurredAt);
        followup.setCreatedAt(OffsetDateTime.now());
        followup.setActor(owner);
        followup.setOrigin(ORIGIN);
        followup.setEntries(Json.write(entries));
        followupMapper.insert(followup);

        PatientMemoryJob job = newJob(owner, patientId, request.operationId(), sequence,
                requestHash, KIND_FOLLOWUP, request.expectedMemoryId());
        job.setFeedbackId(followup.getId());
        jobMapper.insert(job);
        enqueue(job);
        return new PatientMemoryContract.Accepted(toView(job));
    }

    /** 显式刷新：不新增随访，只是把当前事实重新采样发布一版。 */
    @Transactional
    public PatientMemoryContract.Accepted submitRefresh(String owner, String patientId,
                                                        PatientMemoryContract.MemoryRequest request) {
        validateCommon(owner, patientId, request.operationId(), request.expectedMemoryId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", KIND_REFRESH);
        String requestHash = Ids.sha256Hex(Json.write(body));

        PatientMemoryJob existing = findByOperation(patientId, request.operationId());
        if (existing != null) {
            assertSameRequest(existing, requestHash);
            return new PatientMemoryContract.Accepted(toView(existing));
        }
        int sequence = nextSequence(owner, patientId);
        PatientMemoryJob job = newJob(owner, patientId, request.operationId(), sequence,
                requestHash, KIND_REFRESH, request.expectedMemoryId());
        jobMapper.insert(job);
        enqueue(job);
        return new PatientMemoryContract.Accepted(toView(job));
    }

    // ------------------------------------------------------------ 查询与控制

    public PageResult<PatientMemoryContract.FollowupView> followups(String owner, String patientId,
                                                                    int page, int size) {
        memoryService.requirePatient(owner, patientId, false);
        Page<PatientFollowup> result = followupMapper.selectPage(
                new Page<>(PageResult.clampPage(page), PageResult.clampSize(size)),
                new LambdaQueryWrapper<PatientFollowup>()
                        .eq(PatientFollowup::getOwner, owner)
                        .eq(PatientFollowup::getPatientId, patientId)
                        .orderByDesc(PatientFollowup::getRequestSequence));
        return PageResult.of(result, this::toFollowupView);
    }

    public PatientMemoryContract.JobView job(String owner, String patientId, String jobId) {
        return toView(ownedJob(owner, patientId, jobId));
    }

    /** 重试失败任务：只把状态退回 QUEUED 并重新投递事件，不改请求内容。 */
    @Transactional
    public PatientMemoryContract.JobView retry(String owner, String patientId, String jobId) {
        PatientMemoryJob job = ownedJob(owner, patientId, jobId);
        if (STATUS_SUCCEEDED.equals(job.getStatus()) || STATUS_CANCELLED.equals(job.getStatus())) {
            throw BizException.conflict("任务已" + job.getStatus() + "，不能重试");
        }
        job.setStatus(STATUS_QUEUED);
        job.setError(null);
        job.setCompletedAt(null);
        jobMapper.updateById(job);
        enqueue(job);
        return toView(job);
    }

    /** 取消排队或失败的任务。已成功的版本不会被撤销——历史不可改。 */
    @Transactional
    public PatientMemoryContract.JobView cancel(String owner, String patientId, String jobId) {
        PatientMemoryJob job = ownedJob(owner, patientId, jobId);
        if (STATUS_SUCCEEDED.equals(job.getStatus())) {
            throw BizException.conflict("任务已成功，版本已发布且不可撤销");
        }
        job.setStatus(STATUS_CANCELLED);
        job.setCompletedAt(OffsetDateTime.now());
        jobMapper.updateById(job);
        return toView(job);
    }

    // ------------------------------------------------------------ 执行（由 Outbox worker 调用）

    /**
     * 执行一个发布任务。
     *
     * <p>刻意**不**用一个大事务包住 publish：publish 失败会把事务标成 rollback-only，
     * 连带把"任务失败"这条记录也回滚掉，运维就看不到失败。三个阶段各自独立落库，
     * 异常再抛给 Outbox 让它按自己的策略重试。
     *
     * <p>下面三个 mark* 是<b>单条原子 UPDATE</b>，不需要事务；
     * 也没标 {@code @Transactional}——它们由本类内部调用，标了也不会经过代理，
     * 反而让人误以为有事务保护。
     */
    public void execute(String jobId) {
        PatientMemoryJob job = jobMapper.selectById(jobId);
        if (job == null) {
            throw new IllegalStateException("记忆任务不存在: " + jobId);
        }
        if (STATUS_SUCCEEDED.equals(job.getStatus()) || STATUS_CANCELLED.equals(job.getStatus())) {
            return; // Outbox 重放 / 取消后来不及摘掉的事件，幂等跳过
        }
        markRunning(jobId);
        try {
            PatientMemoryVersion version = memoryService.publish(job.getOwner(), job.getPatientId());
            markSucceeded(jobId, version.getId());
            log.info("记忆版本已发布 job={} patient={} memory={}",
                    jobId, job.getPatientId(), version.getId());
        } catch (RuntimeException ex) {
            markFailed(jobId, ex.getMessage());
            throw ex;
        }
    }

    void markRunning(String jobId) {
        PatientMemoryJob job = jobMapper.selectById(jobId);
        if (job == null) {
            return;
        }
        job.setStatus(STATUS_RUNNING);
        job.setStartedAt(OffsetDateTime.now());
        job.setAttemptCount((job.getAttemptCount() == null ? 0 : job.getAttemptCount()) + 1);
        job.setError(null);
        jobMapper.updateById(job);
    }

    void markSucceeded(String jobId, String memoryId) {
        PatientMemoryJob job = jobMapper.selectById(jobId);
        if (job == null) {
            return;
        }
        job.setStatus(STATUS_SUCCEEDED);
        job.setResultMemoryId(memoryId);
        job.setError(null);
        job.setCompletedAt(OffsetDateTime.now());
        jobMapper.updateById(job);
    }

    void markFailed(String jobId, String error) {
        PatientMemoryJob job = jobMapper.selectById(jobId);
        if (job == null) {
            return;
        }
        job.setStatus(STATUS_FAILED);
        job.setError(trim(error, 1000));
        job.setCompletedAt(OffsetDateTime.now());
        jobMapper.updateById(job);
    }

    // ------------------------------------------------------------ 内部

    private void validateCommon(String owner, String patientId,
                                String operationId, String expectedMemoryId) {
        // 行锁：序号分配、幂等查询、指针校验都必须在同一把锁下完成
        memoryService.requirePatient(owner, patientId, true);
        if (operationId == null || !OPERATION_ID.matcher(operationId).matches()) {
            throw BizException.badRequest("operationId 必须是 8~64 位字母、数字、下划线或连字符");
        }
        memoryService.assertExpected(owner, patientId, expectedMemoryId);
    }

    private List<PatientMemoryContract.FeedbackEntry> validateEntries(
            List<PatientMemoryContract.FeedbackEntry> entries) {
        if (entries == null || entries.isEmpty() || entries.size() > MAX_ENTRIES) {
            throw BizException.badRequest("每次随访需提交 1~" + MAX_ENTRIES + " 条反馈");
        }
        Set<String> categories = new LinkedHashSet<>();
        List<PatientMemoryContract.FeedbackEntry> result = new ArrayList<>(entries.size());
        for (PatientMemoryContract.FeedbackEntry entry : entries) {
            String category = entry == null ? null : entry.category();
            String content = entry == null ? null : entry.content();
            if (category == null || !MemorySourceService.CATEGORIES.containsKey(category)) {
                throw BizException.badRequest("随访分类必须是 "
                        + MemorySourceService.CATEGORIES.keySet() + " 之一");
            }
            if (!categories.add(category)) {
                throw BizException.badRequest("同次提交的随访分类不能重复: " + category);
            }
            if (content == null || content.isBlank() || content.length() > MAX_CONTENT) {
                throw BizException.badRequest("随访内容不能为空，且不超过 " + MAX_CONTENT + " 字");
            }
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c < 32 && c != '\n' && c != '\r' && c != '\t') {
                    throw BizException.badRequest("随访内容包含控制字符");
                }
            }
            // 原文照搬：不 strip、不改写、不做任何模型加工
            result.add(new PatientMemoryContract.FeedbackEntry(category, content));
        }
        return result;
    }

    private OffsetDateTime validateOccurredAt(OffsetDateTime occurredAt) {
        if (occurredAt == null) {
            throw BizException.badRequest("occurredAt 必填，且需带时区");
        }
        OffsetDateTime utc = occurredAt.withOffsetSameInstant(ZoneOffset.UTC);
        if (utc.isBefore(MIN_OCCURRED) || utc.isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5))) {
            throw BizException.badRequest("随访时间超出有效范围（不能早于 1900 年，也不能是未来时间）");
        }
        return occurredAt;
    }

    private PatientMemoryJob findByOperation(String patientId, String operationId) {
        return jobMapper.selectOne(new LambdaQueryWrapper<PatientMemoryJob>()
                .eq(PatientMemoryJob::getPatientId, patientId)
                .eq(PatientMemoryJob::getOperationId, operationId)
                .last("limit 1"));
    }

    private void assertSameRequest(PatientMemoryJob existing, String requestHash) {
        if (!requestHash.equals(existing.getRequestHash())) {
            throw new BizException("MEMORY_OPERATION_CONFLICT",
                    "同一 operationId 已用于不同的请求内容，请换一个 operationId", HttpStatus.CONFLICT);
        }
    }

    /** 调用方已持有 patient 行锁，因此 max+1 不会撞号。 */
    private int nextSequence(String owner, String patientId) {
        List<PatientMemoryJob> rows = jobMapper.selectList(
                new LambdaQueryWrapper<PatientMemoryJob>()
                        .eq(PatientMemoryJob::getOwner, owner)
                        .eq(PatientMemoryJob::getPatientId, patientId)
                        .orderByDesc(PatientMemoryJob::getRequestSequence)
                        .last("limit 1"));
        return rows.isEmpty() ? 1
                : (rows.get(0).getRequestSequence() == null ? 0 : rows.get(0).getRequestSequence()) + 1;
    }

    private PatientMemoryJob newJob(String owner, String patientId, String operationId,
                                    int sequence, String requestHash, String kind,
                                    String expectedMemoryId) {
        PatientMemoryJob job = new PatientMemoryJob();
        job.setId(Ids.newId("pmjob"));
        job.setOwner(owner);
        job.setPatientId(patientId);
        job.setOperationId(operationId);
        job.setRequestSequence(sequence);
        job.setRequestHash(requestHash);
        job.setKind(kind);
        job.setExpectedMemoryId(expectedMemoryId);
        job.setStatus(STATUS_QUEUED);
        job.setAttemptCount(0);
        job.setCreatedAt(OffsetDateTime.now());
        return job;
    }

    private void enqueue(PatientMemoryJob job) {
        outboxService.append("patient_memory", job.getId(), EVENT_PUBLISH,
                Map.of("jobId", job.getId()));
    }

    private PatientMemoryJob ownedJob(String owner, String patientId, String jobId) {
        PatientMemoryJob job = jobMapper.selectById(jobId);
        if (job == null || !owner.equals(job.getOwner()) || !patientId.equals(job.getPatientId())) {
            throw BizException.notFound("记忆任务");
        }
        return job;
    }

    private PatientMemoryContract.FollowupView toFollowupView(PatientFollowup row) {
        return new PatientMemoryContract.FollowupView(
                row.getId(), row.getOperationId(),
                row.getRequestSequence() == null ? 0 : row.getRequestSequence(),
                row.getOccurredAt(), row.getCreatedAt(), row.getActor(), row.getOrigin(),
                Json.readList(row.getEntries(), PatientMemoryContract.FeedbackEntry.class));
    }

    private PatientMemoryContract.JobView toView(PatientMemoryJob row) {
        return new PatientMemoryContract.JobView(
                row.getId(), row.getPatientId(), row.getOperationId(),
                row.getRequestSequence() == null ? 0 : row.getRequestSequence(),
                row.getKind(), row.getFeedbackId(), row.getExpectedMemoryId(),
                row.getStatus(), row.getAttemptCount() == null ? 0 : row.getAttemptCount(),
                row.getResultMemoryId(), row.getError(),
                row.getCreatedAt(), row.getStartedAt(), row.getCompletedAt());
    }

    private String trim(String value, int max) {
        if (value == null) {
            return "未知错误";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
