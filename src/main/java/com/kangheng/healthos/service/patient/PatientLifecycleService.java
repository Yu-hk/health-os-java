package com.kangheng.healthos.service.patient;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.contract.patient.PatientContract;
import com.kangheng.healthos.mapper.patient.PatientMapper;
import com.kangheng.healthos.mapper.patient.PatientRevisionMapper;
import com.kangheng.healthos.model.patient.Patient;
import com.kangheng.healthos.model.patient.PatientRevision;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 患者档案的生命周期：草稿 → 正式 → 归档 → 删除，以及每次变更的版本快照。
 *
 * <p>两条不可让步的规则：
 * <ol>
 *   <li><b>写必须带版本号</b>。没有 {@code expectedRevision} 的修改一概拒绝，
 *       否则"打开页面 → 改 → 保存"之间别人的修改会被静默覆盖；</li>
 *   <li><b>状态只按允许的边流转</b>。{@code DELETED} 是终态，任何试图"复活"的请求都报错，
 *       而不是悄悄改回去——删除后再恢复属于合规事件，必须走单独流程。</li>
 * </ol>
 */
@Service
public class PatientLifecycleService {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";
    public static final String STATUS_DELETED = "DELETED";

    public static final String ERROR_CONFLICT = "PATIENT_REVISION_CONFLICT";
    public static final String ERROR_TRANSITION = "PATIENT_STATE_TRANSITION";
    public static final String ERROR_STATE = "PATIENT_STATE_INVALID";

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            STATUS_DRAFT, Set.of(STATUS_ACTIVE, STATUS_DELETED),
            STATUS_ACTIVE, Set.of(STATUS_ARCHIVED, STATUS_DELETED),
            STATUS_ARCHIVED, Set.of(STATUS_ACTIVE, STATUS_DELETED),
            STATUS_DELETED, Set.of());

    private final PatientMapper patientMapper;
    private final PatientRevisionMapper revisionMapper;

    public PatientLifecycleService(PatientMapper patientMapper, PatientRevisionMapper revisionMapper) {
        this.patientMapper = patientMapper;
        this.revisionMapper = revisionMapper;
    }

    // ------------------------------------------------------------ 建档

    @Transactional
    public PatientContract.PatientView create(PatientContract.CreatePatientRequest request, String actor) {
        String name = request.name() == null ? "" : request.name().strip();
        if (name.isEmpty()) {
            throw BizException.badRequest("患者姓名不能为空");
        }
        if (!StringUtils.hasText(request.gender())) {
            throw BizException.badRequest("性别不能为空");
        }
        String externalRef = normalize(request.externalRef());
        if (externalRef != null) {
            Patient existing = findByExternalRef(actor, externalRef);
            if (existing != null) {
                throw new BizException("PATIENT_EXTERNAL_REF_DUPLICATED",
                        "外部业务号已存在：" + externalRef, HttpStatus.CONFLICT);
            }
        }
        OffsetDateTime now = OffsetDateTime.now();
        Patient patient = new Patient();
        patient.setId(Ids.newId("pat"));
        patient.setName(name);
        patient.setGender(request.gender().strip());
        patient.setBirthDate(request.birthDate());
        patient.setPhone(normalize(request.phone()));
        patient.setExternalRef(externalRef);
        patient.setRiskLevel("UNKNOWN");
        patient.setStatus(Boolean.TRUE.equals(request.draft()) ? STATUS_DRAFT : STATUS_ACTIVE);
        patient.setRevision(0);
        patient.setCreatedBy(actor);
        patient.setCreatedAt(now);
        patient.setUpdatedAt(now);
        patientMapper.insert(patient);
        snapshot(patient, actor, "创建档案");
        return PatientService.toView(patient);
    }

    // ------------------------------------------------------------ 编辑

    @Transactional
    public PatientContract.PatientView update(String patientId, PatientContract.UpdatePatientRequest request,
                                              String actor) {
        Patient current = require(patientId, actor);
        requireEditable(current);
        validateRevision(current, request.expectedRevision());

        Patient patch = new Patient();
        patch.setId(patientId);
        if (request.name() != null) {
            String name = request.name().strip();
            if (name.isEmpty()) {
                throw BizException.badRequest("患者姓名不能为空");
            }
            patch.setName(name);
        }
        if (request.gender() != null) {
            if (!StringUtils.hasText(request.gender())) {
                throw BizException.badRequest("性别不能为空");
            }
            patch.setGender(request.gender().strip());
        }
        if (request.birthDate() != null) {
            patch.setBirthDate(request.birthDate());
        }
        if (request.phone() != null) {
            patch.setPhone(normalize(request.phone()));
        }
        if (request.externalRef() != null) {
            String externalRef = normalize(request.externalRef());
            Patient other = externalRef == null ? null : findByExternalRef(
                    current.getCreatedBy(), externalRef);
            if (other != null && !other.getId().equals(patientId)) {
                throw new BizException("PATIENT_EXTERNAL_REF_DUPLICATED",
                        "外部业务号已被其他患者占用：" + externalRef, HttpStatus.CONFLICT);
            }
            patch.setExternalRef(externalRef);
        }
        patch.setRevision(request.expectedRevision() + 1);
        patch.setUpdatedAt(OffsetDateTime.now());

        int affected = patientMapper.update(patch, new LambdaUpdateWrapper<Patient>()
                .eq(Patient::getId, patientId)
                .eq(Patient::getCreatedBy, actor)
                .eq(Patient::getRevision, request.expectedRevision()));
        if (affected == 0) {
            throw conflict(patientId);
        }
        Patient updated = require(patientId, actor);
        snapshot(updated, actor, reasonOf(request.reason(), "修改档案"));
        return PatientService.toView(updated);
    }

    // ------------------------------------------------------------ 状态流转

    public PatientContract.PatientView confirm(String patientId, PatientContract.TransitionRequest request,
                                               String actor) {
        return transition(patientId, STATUS_ACTIVE, request, actor, "确认档案");
    }

    public PatientContract.PatientView archive(String patientId, PatientContract.TransitionRequest request,
                                               String actor) {
        return transition(patientId, STATUS_ARCHIVED, request, actor, "归档档案");
    }

    public PatientContract.PatientView restore(String patientId, PatientContract.TransitionRequest request,
                                               String actor) {
        return transition(patientId, STATUS_ACTIVE, request, actor, "恢复档案");
    }

    @Transactional
    public PatientContract.PatientView transition(String patientId, String target,
                                                  PatientContract.TransitionRequest request,
                                                  String actor, String defaultReason) {
        Patient current = require(patientId, actor);
        Set<String> allowed = ALLOWED.getOrDefault(current.getStatus(), Set.of());
        if (!allowed.contains(target)) {
            throw new BizException(ERROR_TRANSITION,
                    "不允许的状态流转：" + current.getStatus() + " → " + target, HttpStatus.CONFLICT);
        }
        validateRevision(current, request.expectedRevision());

        OffsetDateTime now = OffsetDateTime.now();
        Patient patch = new Patient();
        patch.setId(patientId);
        patch.setStatus(target);
        patch.setRevision(request.expectedRevision() + 1);
        patch.setUpdatedAt(now);
        if (STATUS_ARCHIVED.equals(target)) {
            patch.setArchivedAt(now);
        }
        LambdaUpdateWrapper<Patient> wrapper = new LambdaUpdateWrapper<Patient>()
                .eq(Patient::getId, patientId)
                .eq(Patient::getCreatedBy, actor)
                .eq(Patient::getRevision, request.expectedRevision());
        int affected;
        if (STATUS_ACTIVE.equals(target)) {
            // 恢复时清掉归档时间，避免"已激活但归档时间还在"。
            // 这里用字符串列名：LambdaUpdateWrapper#set 会立即解析实体元数据，
            // 放在没有 MyBatis 上下文的单测里会直接抛错；清空同样必须带版本条件。
            UpdateWrapper<Patient> clearWrapper = new UpdateWrapper<Patient>()
                    .eq("id", patientId)
                    .eq("created_by", actor)
                    .eq("revision", request.expectedRevision())
                    .set("archived_at", null);
            affected = patientMapper.update(patch, clearWrapper);
        } else {
            affected = patientMapper.update(patch, wrapper);
        }
        if (affected == 0) {
            throw conflict(patientId);
        }
        Patient updated = require(patientId, actor);
        snapshot(updated, actor, reasonOf(request.reason(), defaultReason));
        return PatientService.toView(updated);
    }

    /** 删除前的落库动作：标记终态。真正的资源擦除由删除任务异步完成。 */
    @Transactional
    public Patient markDeleted(String patientId, int expectedRevision, String actor) {
        Patient patch = new Patient();
        patch.setId(patientId);
        patch.setStatus(STATUS_DELETED);
        patch.setRevision(expectedRevision + 1);
        patch.setDeletedAt(OffsetDateTime.now());
        patch.setUpdatedAt(OffsetDateTime.now());
        int affected = patientMapper.update(patch, new LambdaUpdateWrapper<Patient>()
                .eq(Patient::getId, patientId)
                .eq(Patient::getCreatedBy, actor)
                .eq(Patient::getRevision, expectedRevision));
        if (affected == 0) {
            throw conflict(patientId);
        }
        Patient updated = require(patientId, actor);
        snapshot(updated, actor, "确认删除");
        return updated;
    }

    // ------------------------------------------------------------ 批次导入用的 upsert

    /**
     * 按外部业务号 upsert。批次重跑时同一档案号只会更新、不会重复建档。
     *
     * @return 建档或更新后的患者；被删除（终态）的档案一律拒绝更新，由调用方记为失败行
     */
    @Transactional
    public Patient upsertFromExternal(String externalRef, String name, String gender,
                                      LocalDate birthDate, String phone, String actor) {
        Patient existing = externalRef == null ? null : findByExternalRef(actor, externalRef);
        if (existing != null) {
            if (STATUS_DELETED.equals(existing.getStatus())) {
                throw new BizException(ERROR_STATE, "档案已删除，不能通过批次导入更新", HttpStatus.CONFLICT);
            }
            int expected = existing.getRevision() == null ? 0 : existing.getRevision();
            Patient patch = new Patient();
            patch.setId(existing.getId());
            patch.setName(name);
            patch.setGender(gender);
            patch.setBirthDate(birthDate);
            patch.setPhone(phone);
            patch.setRevision(expected + 1);
            patch.setUpdatedAt(OffsetDateTime.now());
            int affected = patientMapper.update(patch, new LambdaUpdateWrapper<Patient>()
                    .eq(Patient::getId, existing.getId())
                    .eq(Patient::getCreatedBy, actor)
                    .eq(Patient::getRevision, expected));
            if (affected == 0) {
                throw conflict(existing.getId());
            }
            Patient updated = require(existing.getId(), actor);
            snapshot(updated, actor, "批次导入更新");
            return updated;
        }

        OffsetDateTime now = OffsetDateTime.now();
        Patient patient = new Patient();
        patient.setId(Ids.newId("pat"));
        patient.setName(name);
        patient.setGender(gender);
        patient.setBirthDate(birthDate);
        patient.setPhone(phone);
        patient.setExternalRef(externalRef);
        patient.setRiskLevel("UNKNOWN");
        patient.setStatus(STATUS_ACTIVE);
        patient.setRevision(0);
        patient.setCreatedBy(actor);
        patient.setCreatedAt(now);
        patient.setUpdatedAt(now);
        patientMapper.insert(patient);
        snapshot(patient, actor, "批次导入建档");
        return patient;
    }

    // ------------------------------------------------------------ 查询

    /**
     * 取患者档案并校验归属。
     *
     * <p>越权时返回 404 而不是 403：403 等于告诉对方"这个 ID 确实存在"，
     * 那本身就是信息泄露。原项目用 {@code owned_patient()} 做同一件事。
     */
    public Patient require(String patientId, String owner) {
        Patient patient = patientMapper.selectOne(new LambdaQueryWrapper<Patient>()
                .eq(Patient::getId, patientId)
                .eq(Patient::getCreatedBy, owner)
                .last("limit 1"));
        if (patient == null) {
            throw BizException.notFound("患者");
        }
        return patient;
    }

    /**
     * 不校验归属地取档案。
     *
     * <p>只给后台 worker 用：outbox 任务按 ID 派发，worker 并不知道调用者是谁，
     * 它执行的是系统级动作。用户态入口一律走 {@link #require(String, String)}。
     */
    public Patient requireInternal(String patientId) {
        Patient patient = patientMapper.selectById(patientId);
        if (patient == null) {
            throw BizException.notFound("患者");
        }
        return patient;
    }

    public Patient findByExternalRef(String owner, String externalRef) {
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(externalRef)) {
            return null;
        }
        return patientMapper.selectOne(new LambdaQueryWrapper<Patient>()
                .eq(Patient::getCreatedBy, owner)
                .eq(Patient::getExternalRef, externalRef)
                .last("limit 1"));
    }

    public List<PatientContract.RevisionView> revisions(String patientId, String owner) {
        require(patientId, owner);
        return revisionMapper.selectList(new LambdaQueryWrapper<PatientRevision>()
                        .eq(PatientRevision::getPatientId, patientId)
                        .eq(PatientRevision::getOwner, owner)
                        .orderByDesc(PatientRevision::getRevision))
                .stream()
                .map(rev -> new PatientContract.RevisionView(rev.getId(), rev.getRevision(), rev.getActor(),
                        rev.getReason(), rev.getSha256(), rev.getCreatedAt()))
                .toList();
    }

    /** 回放某一版快照，用于核对"当时到底存了什么"。 */
    public Map<String, Object> revisionSnapshot(String patientId, int revision, String owner) {
        require(patientId, owner);
        PatientRevision rev = revisionMapper.selectOne(new LambdaQueryWrapper<PatientRevision>()
                .eq(PatientRevision::getPatientId, patientId)
                .eq(PatientRevision::getOwner, owner)
                .eq(PatientRevision::getRevision, revision));
        if (rev == null) {
            throw BizException.notFound("版本 " + revision);
        }
        return Json.readMap(rev.getSnapshot());
    }

    // ------------------------------------------------------------ 内部

    /** 每次成功变更都留一条快照，sha256 用于事后验证快照未被改动。 */
    void snapshot(Patient patient, String actor, String reason) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("id", patient.getId());
        content.put("name", patient.getName());
        content.put("gender", patient.getGender());
        content.put("birthDate", patient.getBirthDate() == null ? null : patient.getBirthDate().toString());
        content.put("phone", patient.getPhone());
        content.put("externalRef", patient.getExternalRef());
        content.put("status", patient.getStatus());
        content.put("riskLevel", patient.getRiskLevel());
        String json = Json.write(content);
        PatientRevision revision = new PatientRevision();
        revision.setId(Ids.newId("prev"));
        revision.setPatientId(patient.getId());
        revision.setOwner(patient.getCreatedBy());
        revision.setRevision(patient.getRevision() == null ? 0 : patient.getRevision());
        revision.setActor(actor);
        revision.setReason(reason);
        revision.setSnapshot(json);
        revision.setSha256(Ids.sha256Hex(json.getBytes(StandardCharsets.UTF_8)));
        revision.setCreatedAt(OffsetDateTime.now());
        try {
            revisionMapper.insert(revision);
        } catch (DuplicateKeyException ex) {
            // 唯一约束是并发保护的最后一道：同一版本被写了两次，说明有并发修改
            throw conflict(patient.getId());
        }
    }

    private void validateRevision(Patient patient, Integer expected) {
        if (expected == null) {
            throw BizException.badRequest("expectedRevision 必填：不带版本号的写请求会被拒绝");
        }
        int actual = patient.getRevision() == null ? 0 : patient.getRevision();
        if (actual != expected) {
            throw conflict(patient.getId());
        }
    }

    private void requireEditable(Patient patient) {
        if (STATUS_DELETED.equals(patient.getStatus())) {
            throw new BizException(ERROR_STATE, "档案已删除，不能再修改", HttpStatus.CONFLICT);
        }
        if (STATUS_ARCHIVED.equals(patient.getStatus())) {
            throw new BizException(ERROR_STATE, "档案已归档，请先恢复再修改", HttpStatus.CONFLICT);
        }
    }

    private BizException conflict(String patientId) {
        Patient latest = patientMapper.selectById(patientId);
        String hint = latest == null ? "档案已不存在"
                : "当前版本为 " + latest.getRevision() + "，请刷新后重试";
        return new BizException(ERROR_CONFLICT, "版本冲突：" + hint, HttpStatus.CONFLICT);
    }

    private static String reasonOf(String reason, String fallback) {
        return StringUtils.hasText(reason) ? reason.strip() : fallback;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
