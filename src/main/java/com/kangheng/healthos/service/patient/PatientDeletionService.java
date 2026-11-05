package com.kangheng.healthos.service.patient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.contract.patient.PatientContract;
import com.kangheng.healthos.mapper.patient.PatientBatchRowMapper;
import com.kangheng.healthos.mapper.patient.PatientDeletionResourceMapper;
import com.kangheng.healthos.mapper.patient.PatientDeletionTaskMapper;
import com.kangheng.healthos.mapper.patient.PatientMapper;
import com.kangheng.healthos.mapper.patient.PatientMetricMapper;
import com.kangheng.healthos.mapper.patient.PatientObjectMapper;
import com.kangheng.healthos.mapper.patient.PatientRevisionMapper;
import com.kangheng.healthos.model.patient.Patient;
import com.kangheng.healthos.model.patient.PatientBatchRow;
import com.kangheng.healthos.model.patient.PatientDeletionResource;
import com.kangheng.healthos.model.patient.PatientDeletionTask;
import com.kangheng.healthos.model.patient.PatientMetric;
import com.kangheng.healthos.model.patient.PatientObjectEntity;
import com.kangheng.healthos.model.patient.PatientRevision;
import com.kangheng.healthos.service.outbox.OutboxService;
import com.kangheng.healthos.spi.storage.ObjectStorage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 删除：预览 → 确认 → 擦除 → 归档台账。
 *
 * <p>四个动作分开，各有各的理由：
 * <ul>
 *   <li><b>预览</b>先算清"会删掉什么"（指标几条、附件几个、批次行几行），
 *       并给一个一次性令牌；</li>
 *   <li><b>确认</b>必须同时带令牌与版本号，防止"预览完很久之后，档案已经被改过"；</li>
 *   <li><b>擦除</b>异步执行、逐条记录，某一条失败能看出卡在哪；</li>
 *   <li><b>台账</b>归档到对象存储并登记 sha256，事后可以证明"当时删了什么、没删什么"。</li>
 * </ul>
 *
 * <p>台账里会保留姓名与脱敏手机号（仅后四位）——它是合规凭证，
 * 不是第二个数据副本；访问控制按审计日志的级别对待。
 */
@Service
public class PatientDeletionService {

    private static final Logger log = LoggerFactory.getLogger(PatientDeletionService.class);

    public static final String EVENT_ERASE = "patient.deletion.erase";

    private static final String TASK_PREVIEW = "PREVIEW";
    private static final String TASK_PENDING = "PENDING";
    private static final String TASK_RUNNING = "RUNNING";
    private static final String TASK_COMPLETED = "COMPLETED";
    private static final String TASK_FAILED = "FAILED";

    private static final String LEDGER_PREFIX = "ledger/patient-deletion";

    private final PatientDeletionTaskMapper taskMapper;
    private final PatientDeletionResourceMapper resourceMapper;
    private final PatientMapper patientMapper;
    private final PatientMetricMapper metricMapper;
    private final PatientRevisionMapper revisionMapper;
    private final PatientObjectMapper objectMapper;
    private final PatientBatchRowMapper batchRowMapper;
    private final PatientLifecycleService lifecycle;
    private final OutboxService outboxService;
    private final ObjectStorage storage;

    public PatientDeletionService(PatientDeletionTaskMapper taskMapper,
                                  PatientDeletionResourceMapper resourceMapper,
                                  PatientMapper patientMapper,
                                  PatientMetricMapper metricMapper,
                                  PatientRevisionMapper revisionMapper,
                                  PatientObjectMapper objectMapper,
                                  PatientBatchRowMapper batchRowMapper,
                                  PatientLifecycleService lifecycle,
                                  OutboxService outboxService,
                                  ObjectStorage storage) {
        this.taskMapper = taskMapper;
        this.resourceMapper = resourceMapper;
        this.patientMapper = patientMapper;
        this.metricMapper = metricMapper;
        this.revisionMapper = revisionMapper;
        this.objectMapper = objectMapper;
        this.batchRowMapper = batchRowMapper;
        this.lifecycle = lifecycle;
        this.outboxService = outboxService;
        this.storage = storage;
    }

    // ------------------------------------------------------------ 预览

    @Transactional
    public PatientContract.DeletionPreview preview(String patientId, String reason, String actor) {
        Patient patient = lifecycle.require(patientId, actor);
        if (PatientLifecycleService.STATUS_DELETED.equals(patient.getStatus())) {
            throw new BizException(PatientLifecycleService.ERROR_STATE, "档案已删除",
                    HttpStatus.CONFLICT);
        }
        List<Row> resources = collect(patientId);
        String token = Ids.sha256Hex((patientId + "|" + patient.getRevision() + "|" + actor + "|"
                + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        String safeReason = StringUtils.hasText(reason) ? truncate(reason.strip(), 200) : "用户申请删除";

        OffsetDateTime now = OffsetDateTime.now();
        PatientDeletionTask task = new PatientDeletionTask();
        task.setId(Ids.newId("pdt"));
        task.setPatientId(patientId);
        task.setOwner(actor);
        task.setStatus(TASK_PREVIEW);
        task.setRequestedBy(actor);
        task.setReason(safeReason);
        task.setPreviewToken(token);
        task.setResourceCount(resources.size());
        task.setCreatedAt(now);
        taskMapper.insert(task);

        Map<String, Long> byKind = new LinkedHashMap<>();
        for (Row row : resources) {
            PatientDeletionResource entity = new PatientDeletionResource();
            entity.setId(Ids.newId("pdr"));
            entity.setTaskId(task.getId());
            entity.setOwner(actor);
            entity.setResourceKind(row.kind());
            entity.setResourceKey(row.key());
            entity.setScope(row.scope());
            entity.setErased(false);
            entity.setCreatedAt(now);
            resourceMapper.insert(entity);
            byKind.merge(row.kind(), 1L, Long::sum);
        }
        return new PatientContract.DeletionPreview(task.getId(), patientId, task.getStatus(), token,
                resources.size(), byKind, safeReason);
    }

    // ------------------------------------------------------------ 确认

    @Transactional
    public PatientContract.DeletionTaskView confirm(String patientId, String taskId,
                                                    PatientContract.DeletionConfirmRequest request,
                                                    String actor) {
        PatientDeletionTask task = requireTask(taskId, actor);
        if (!patientId.equals(task.getPatientId())) {
            throw BizException.notFound("删除任务");
        }
        if (!TASK_PREVIEW.equals(task.getStatus())) {
            throw new BizException("DELETION_TASK_STATE", "任务已确认过，当前状态 " + task.getStatus(),
                    HttpStatus.CONFLICT);
        }
        if (!secureEquals(task.getPreviewToken(), request.previewToken())) {
            throw new BizException("DELETION_TOKEN_INVALID", "预览令牌不匹配", HttpStatus.FORBIDDEN);
        }
        // 版本号校验交给生命周期服务：档案在预览之后被改过就拒绝，避免删掉没看过的内容
        lifecycle.markDeleted(patientId, request.expectedRevision(), actor);

        task.setStatus(TASK_PENDING);
        task.setConfirmedAt(OffsetDateTime.now());
        taskMapper.updateById(task);
        outboxService.append("patient_deletion", taskId, EVENT_ERASE, Map.of("taskId", taskId));
        log.info("患者删除已确认 task={} patient={} by={}", taskId, patientId, actor);
        return view(task);
    }

    // ------------------------------------------------------------ 执行

    /** 由 outbox worker 调用。幂等：已完成的直接返回，台账已归档的不重复归档。 */
    public void process(String taskId) {
        PatientDeletionTask task = requireTaskInternal(taskId);
        if (TASK_COMPLETED.equals(task.getStatus())) {
            return;
        }
        task.setStatus(TASK_RUNNING);
        taskMapper.updateById(task);
        try {
            List<PatientDeletionResource> resources = resourceMapper.selectList(
                    new LambdaQueryWrapper<PatientDeletionResource>()
                            .eq(PatientDeletionResource::getTaskId, taskId)
                            .orderByAsc(PatientDeletionResource::getCreatedAt));
            for (PatientDeletionResource resource : resources) {
                if (Boolean.TRUE.equals(resource.getErased())) {
                    continue;
                }
                erase(resource);
                resource.setErased(true);
                resource.setErasedAt(OffsetDateTime.now());
                resourceMapper.updateById(resource);
            }
            if (!StringUtils.hasText(task.getLedgerKey())) {
                archiveLedger(task, resources);
            }
            // 最后才删患者主记录：前面任一步失败时，档案还在，可以重试
            patientMapper.deleteById(task.getPatientId());
            task.setStatus(TASK_COMPLETED);
            task.setCompletedAt(OffsetDateTime.now());
            task.setErrorMessage(null);
            taskMapper.updateById(task);
            log.info("患者删除完成 task={} patient={} resources={}", taskId, task.getPatientId(),
                    resources.size());
        } catch (RuntimeException ex) {
            task.setStatus(TASK_FAILED);
            task.setErrorMessage(truncate(ex.getMessage() == null ? ex.toString() : ex.getMessage(), 1000));
            taskMapper.updateById(task);
            throw ex;
        }
    }

    private void erase(PatientDeletionResource resource) {
        switch (resource.getResourceKind()) {
            case "METRIC" -> metricMapper.deleteById(resource.getResourceKey());
            case "REVISION" -> revisionMapper.deleteById(resource.getResourceKey());
            case "OBJECT" -> {
                objectMapper.deleteById(resource.getResourceKey());
                if (StringUtils.hasText(resource.getScope())) {
                    try {
                        storage.delete(resource.getScope());
                    } catch (RuntimeException ex) {
                        // 文件可能已被别处删掉；元数据已经清理，残留交给孤儿扫描
                        log.warn("删除附件对象失败 key={}", resource.getScope(), ex);
                    }
                }
            }
            case "BATCH_ROW" -> batchRowMapper.update(null, new LambdaUpdateWrapper<PatientBatchRow>()
                    .eq(PatientBatchRow::getId, resource.getResourceKey())
                    .set(PatientBatchRow::getPatientId, null)
                    .set(PatientBatchRow::getStatus, "SKIPPED")
                    .set(PatientBatchRow::getUpdatedAt, OffsetDateTime.now()));
            default -> log.debug("未知资源类型，跳过 kind={} key={}",
                    resource.getResourceKind(), resource.getResourceKey());
        }
    }

    private void archiveLedger(PatientDeletionTask task, List<PatientDeletionResource> resources) {
        Map<String, Object> ledger = new LinkedHashMap<>();
        ledger.put("schemaVersion", 1);
        ledger.put("taskId", task.getId());
        ledger.put("patientId", task.getPatientId());
        ledger.put("requestedBy", task.getRequestedBy());
        ledger.put("reason", task.getReason());
        ledger.put("previewedAt", String.valueOf(task.getCreatedAt()));
        ledger.put("confirmedAt", String.valueOf(task.getConfirmedAt()));
        ledger.put("completedAt", OffsetDateTime.now().toString());

        List<Map<String, Object>> items = new ArrayList<>();
        for (PatientDeletionResource resource : resources) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", resource.getResourceKind());
            item.put("key", resource.getResourceKey());
            item.put("scope", resource.getScope());
            item.put("erased", Boolean.TRUE.equals(resource.getErased()));
            items.add(item);
        }
        ledger.put("resources", items);

        Map<String, Long> byKind = new LinkedHashMap<>();
        for (PatientDeletionResource resource : resources) {
            byKind.merge(resource.getResourceKind(), 1L, Long::sum);
        }
        ledger.put("counts", byKind);

        byte[] bytes = Json.write(ledger).getBytes(StandardCharsets.UTF_8);
        String objectKey = LEDGER_PREFIX + "/" + task.getId() + ".json";
        ObjectStorage.StoredObject stored = storage.putAt(objectKey, bytes);
        task.setLedgerKey(stored.objectKey());
        task.setLedgerSha256(stored.sha256());
    }

    // ------------------------------------------------------------ 查询

    public PatientContract.DeletionTaskView view(String taskId, String owner) {
        return view(requireTask(taskId, owner));
    }

    public List<PatientContract.DeletionTaskView> list(String patientId, String owner) {
        return taskMapper.selectList(new LambdaQueryWrapper<PatientDeletionTask>()
                        .eq(PatientDeletionTask::getPatientId, patientId)
                        .eq(PatientDeletionTask::getOwner, owner)
                        .orderByDesc(PatientDeletionTask::getCreatedAt))
                .stream().map(this::view).toList();
    }

    /** 台账回读：每次都重算 sha256 并与登记值比对，改过一个字节就会显示 verified=false。 */
    public PatientContract.LedgerView ledger(String taskId, String owner) {
        PatientDeletionTask task = requireTask(taskId, owner);
        if (!StringUtils.hasText(task.getLedgerKey())) {
            throw new BizException("DELETION_LEDGER_MISSING", "台账尚未生成（任务状态 " + task.getStatus() + "）",
                    HttpStatus.CONFLICT);
        }
        byte[] bytes = storage.get(task.getLedgerKey());
        String sha = Ids.sha256Hex(bytes);
        boolean verified = sha.equals(task.getLedgerSha256());
        return new PatientContract.LedgerView(taskId, sha, verified,
                Json.readMap(new String(bytes, StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------ 内部

    private record Row(String kind, String key, String scope) {
    }

    private List<Row> collect(String patientId) {
        List<Row> rows = new ArrayList<>();
        for (PatientMetric metric : metricMapper.selectList(new LambdaQueryWrapper<PatientMetric>()
                .eq(PatientMetric::getPatientId, patientId))) {
            rows.add(new Row("METRIC", metric.getId(), metric.getCode()));
        }
        for (PatientRevision revision : revisionMapper.selectList(new LambdaQueryWrapper<PatientRevision>()
                .eq(PatientRevision::getPatientId, patientId))) {
            rows.add(new Row("REVISION", revision.getId(), "rev-" + revision.getRevision()));
        }
        for (PatientObjectEntity object : objectMapper.selectList(new LambdaQueryWrapper<PatientObjectEntity>()
                .eq(PatientObjectEntity::getPatientId, patientId)
                .eq(PatientObjectEntity::getStatus, "ACTIVE"))) {
            rows.add(new Row("OBJECT", object.getId(), object.getObjectKey()));
        }
        for (PatientBatchRow batchRow : batchRowMapper.selectList(new LambdaQueryWrapper<PatientBatchRow>()
                .eq(PatientBatchRow::getPatientId, patientId))) {
            rows.add(new Row("BATCH_ROW", batchRow.getId(), batchRow.getBatchId()));
        }
        return rows;
    }

    /**
     * 取删除任务并校验归属。
     *
     * <p>台账里留着姓名与脱敏手机号，越权时返回 404 而不是 403。
     */
    private PatientDeletionTask requireTask(String taskId, String owner) {
        PatientDeletionTask task = taskMapper.selectOne(new LambdaQueryWrapper<PatientDeletionTask>()
                .eq(PatientDeletionTask::getId, taskId)
                .eq(PatientDeletionTask::getOwner, owner)
                .last("limit 1"));
        if (task == null) {
            throw BizException.notFound("删除任务");
        }
        return task;
    }

    /** 不校验归属地取任务：只给后台 worker 用。 */
    private PatientDeletionTask requireTaskInternal(String taskId) {
        PatientDeletionTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw BizException.notFound("删除任务");
        }
        return task;
    }

    private PatientContract.DeletionTaskView view(PatientDeletionTask task) {
        List<PatientContract.DeletionResourceView> resources = resourceMapper.selectList(
                        new LambdaQueryWrapper<PatientDeletionResource>()
                                .eq(PatientDeletionResource::getTaskId, task.getId())
                                .orderByAsc(PatientDeletionResource::getCreatedAt))
                .stream()
                .map(item -> new PatientContract.DeletionResourceView(item.getResourceKind(),
                        item.getResourceKey(), item.getScope(), Boolean.TRUE.equals(item.getErased())))
                .toList();
        return new PatientContract.DeletionTaskView(task.getId(), task.getPatientId(), task.getStatus(),
                task.getRequestedBy(), task.getReason(),
                task.getResourceCount() == null ? 0 : task.getResourceCount(),
                task.getLedgerKey(), task.getLedgerSha256(), task.getErrorMessage(),
                task.getCreatedAt(), task.getConfirmedAt(), task.getCompletedAt(), resources);
    }

    private static boolean secureEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
