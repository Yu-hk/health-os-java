package com.kangheng.healthos.service.patient;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import com.kangheng.healthos.common.AccessControl;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.common.PageResult;
import com.kangheng.healthos.contract.patient.PatientMemoryContract;
import com.kangheng.healthos.mapper.patient.PatientMapper;
import com.kangheng.healthos.mapper.patient.PatientMemoryStateMapper;
import com.kangheng.healthos.mapper.patient.PatientMemoryVersionMapper;
import com.kangheng.healthos.model.patient.Patient;
import com.kangheng.healthos.model.patient.PatientMemoryState;
import com.kangheng.healthos.model.patient.PatientMemoryVersion;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 患者长效记忆：不可变版本链 + 当前指针。
 *
 * <p>对齐原工程 {@code memory_service.initialize / publish_version / memory_page}。
 * 三个不让步的语义：
 * <ol>
 *   <li><b>版本只追加</b>：刷新产生新版本并移动指针，历史版本一行都不改；</li>
 *   <li><b>发布串行</b>：所有写在 patient 行上加 {@code FOR UPDATE}，
 *       两个并发刷新不会拿到同一个版本号；</li>
 *   <li><b>归属隔离</b>：版本、指针、随访、任务逐表带 owner，越权一律 404。</li>
 * </ol>
 *
 * <p>关于 {@code expectedMemoryId}：它在**提交时**做乐观并发校验（防止用户基于过期版本做决定），
 * 但**执行时不再校验**。因为本工程的执行器每次都重新采集全量事实，
 * 旧任务后执行反而更新、不会覆盖新结果；原工程需要 fencing token 是因为它有跨进程租约，
 * 旧 worker 可能带着过期输入抢先写回。这个差异写在 README §9，不假装等价。
 */
@Service
public class PatientMemoryService {

    public static final String ERROR_VERSION_CONFLICT = "MEMORY_VERSION_CONFLICT";

    private final PatientMapper patientMapper;
    private final PatientMemoryVersionMapper versionMapper;
    private final PatientMemoryStateMapper stateMapper;
    private final MemorySourceService sourceService;
    private final MemoryMarkdownRenderer renderer;

    public PatientMemoryService(PatientMapper patientMapper,
                                PatientMemoryVersionMapper versionMapper,
                                PatientMemoryStateMapper stateMapper,
                                MemorySourceService sourceService,
                                MemoryMarkdownRenderer renderer) {
        this.patientMapper = patientMapper;
        this.versionMapper = versionMapper;
        this.stateMapper = stateMapper;
        this.sourceService = sourceService;
        this.renderer = renderer;
    }

    /** 导出结果。contentHash 会写进响应头 X-Content-SHA256。 */
    public record ExportFile(String filename, String contentType, byte[] content, String contentHash) {
    }

    /** 当前生效版本；尚未初始化时明确 404，不返回一份空壳记忆。 */
    public PatientMemoryContract.VersionView current(String owner, String patientId) {
        requirePatient(owner, patientId, false);
        PatientMemoryState state = stateMapper.selectById(patientId);
        if (state == null) {
            throw new BizException("MEMORY_NOT_INITIALIZED",
                    "该患者尚未初始化长效记忆", HttpStatus.NOT_FOUND);
        }
        return toView(ownedVersion(owner, patientId, state.getCurrentMemoryId()), true);
    }

    /**
     * 初始化：已有指针时幂等返回当前版本，否则同步发布第一版。
     *
     * <p>同步而非异步是有意的：初始化是后续所有操作的起点，
     * 返回一个 202 + jobId 会让调用方还得再轮询一次才知道能不能继续。
     */
    @Transactional
    public PatientMemoryContract.VersionView initialize(String owner, String patientId) {
        requirePatient(owner, patientId, true);
        PatientMemoryState state = stateMapper.selectById(patientId);
        if (state != null) {
            return toView(ownedVersion(owner, patientId, state.getCurrentMemoryId()), true);
        }
        return toView(publishLocked(owner, patientId), true);
    }

    /** 版本列表（倒序，不含正文）。 */
    public PageResult<PatientMemoryContract.VersionSummary> versions(String owner, String patientId,
                                                                     int page, int size) {
        requirePatient(owner, patientId, false);
        Page<PatientMemoryVersion> result = versionMapper.selectPage(
                new Page<>(PageResult.clampPage(page), PageResult.clampSize(size)),
                new LambdaQueryWrapper<PatientMemoryVersion>()
                        .eq(PatientMemoryVersion::getOwner, owner)
                        .eq(PatientMemoryVersion::getPatientId, patientId)
                        .orderByDesc(PatientMemoryVersion::getVersion));
        return PageResult.of(result, row -> new PatientMemoryContract.VersionSummary(
                row.getId(), row.getVersion() == null ? 0 : row.getVersion(),
                row.getContentHash(), row.getSourceHash(),
                Json.readList(row.getSources(), Map.class).size(),
                row.getSampledAt(), row.getCreatedAt()));
    }

    public PatientMemoryContract.VersionView version(String owner, String patientId, String memoryId) {
        requirePatient(owner, patientId, false);
        return toView(ownedVersion(owner, patientId, memoryId), true);
    }

    /** 导出 Markdown。文件名带版本号，响应头带内容哈希供离线校验。 */
    public ExportFile export(String owner, String patientId, String memoryId) {
        requirePatient(owner, patientId, false);
        PatientMemoryVersion row = ownedVersion(owner, patientId, memoryId);
        return new ExportFile("patient-memory-v" + row.getVersion() + ".md",
                "text/markdown;charset=UTF-8",
                row.getMarkdown().getBytes(StandardCharsets.UTF_8), row.getContentHash());
    }

    /**
     * 发布一个新版本。供异步任务调用；调用方负责事务边界之外的状态回写。
     *
     * <p>这里**不**校验 expectedMemoryId（理由见类注释），只在 patient 行上串行。
     */
    @Transactional
    public PatientMemoryVersion publish(String owner, String patientId) {
        requirePatient(owner, patientId, true);
        return publishLocked(owner, patientId);
    }

    /** 提交时的乐观并发校验：客户端看到的当前版本必须和服务端一致。 */
    public void assertExpected(String owner, String patientId, String expectedMemoryId) {
        PatientMemoryState state = stateMapper.selectById(patientId);
        String currentId = state == null ? null : state.getCurrentMemoryId();
        if (!Objects.equals(currentId, expectedMemoryId)) {
            throw new BizException(ERROR_VERSION_CONFLICT,
                    "记忆版本已变化，请重新读取当前版本后再提交（当前："
                            + (currentId == null ? "尚未初始化" : currentId) + "）",
                    HttpStatus.CONFLICT);
        }
    }

    /** 供任务服务读取当前版本 ID（不抛 404）。 */
    public String currentId(String patientId) {
        PatientMemoryState state = stateMapper.selectById(patientId);
        return state == null ? null : state.getCurrentMemoryId();
    }

    // ------------------------------------------------------------ 内部

    /**
     * 调用方必须已持有 patient 行锁。这里不再重复加锁，避免嵌套事务里出现两次 FOR UPDATE。
     */
    private PatientMemoryVersion publishLocked(String owner, String patientId) {
        PatientMemoryState state = stateMapper.selectById(patientId);
        if (state != null && !owner.equals(state.getOwner())) {
            throw new BizException("MEMORY_STATE_CONFLICT", "记忆指针归属异常", HttpStatus.CONFLICT);
        }
        int version = state == null ? 1 : (state.getVersion() == null ? 0 : state.getVersion()) + 1;
        OffsetDateTime sampledAt = OffsetDateTime.now();
        MemorySourceService.Capture capture = sourceService.capture(owner, patientId, sampledAt);
        String markdown = renderer.render(patientId, version, capture.data(),
                capture.sources(), sampledAt.toString());
        String contentHash = Ids.sha256Hex(markdown);
        String sourceHash = Ids.sha256Hex(Json.write(withoutSampledAt(capture.sources())));

        PatientMemoryVersion row = new PatientMemoryVersion();
        row.setId(Ids.newId("pmem"));
        row.setOwner(owner);
        row.setPatientId(patientId);
        row.setVersion(version);
        row.setSchemaVersion(MemorySourceService.SCHEMA_VERSION);
        row.setGenerationMode(MemorySourceService.GENERATION_MODE);
        row.setContentHash(contentHash);
        row.setSourceHash(sourceHash);
        row.setData(Json.write(capture.data()));
        row.setSources(Json.write(capture.sources()));
        row.setMarkdown(markdown);
        row.setSampledAt(sampledAt);
        row.setCreatedAt(sampledAt);
        versionMapper.insert(row);

        if (state == null) {
            PatientMemoryState created = new PatientMemoryState();
            created.setPatientId(patientId);
            created.setOwner(owner);
            created.setCurrentMemoryId(row.getId());
            created.setVersion(version);
            created.setUpdatedAt(sampledAt);
            stateMapper.insert(created);
        } else {
            state.setCurrentMemoryId(row.getId());
            state.setVersion(version);
            state.setUpdatedAt(sampledAt);
            stateMapper.updateById(state);
        }
        return row;
    }

    /** 采样时间每次都不同，不能进 sourceHash，否则"来源没变"也永远算不出同一个值。 */
    private List<Map<String, Object>> withoutSampledAt(List<Map<String, Object>> sources) {
        List<Map<String, Object>> result = new ArrayList<>(sources.size());
        for (Map<String, Object> source : sources) {
            Map<String, Object> copy = new LinkedHashMap<>(source);
            copy.remove("sampledAt");
            result.add(copy);
        }
        return result;
    }

    private PatientMemoryVersion ownedVersion(String owner, String patientId, String memoryId) {
        PatientMemoryVersion row = versionMapper.selectById(memoryId);
        if (row == null || !owner.equals(row.getOwner()) || !patientId.equals(row.getPatientId())) {
            throw BizException.notFound("记忆版本");
        }
        return row;
    }

    /**
     * @param lock 为 true 时用 FOR UPDATE 锁住患者行，写操作必须传 true
     */
    Patient requirePatient(String owner, String patientId, boolean lock) {
        Patient patient = lock ? patientMapper.lockForUpdate(patientId) : patientMapper.selectById(patientId);
        // 越权一律 404：403 等于承认"这个患者确实存在"
        if (patient == null || !AccessControl.sameUser(owner, patient.getCreatedBy())) {
            throw BizException.notFound("患者档案");
        }
        return patient;
    }

    @SuppressWarnings("unchecked")
    private PatientMemoryContract.VersionView toView(PatientMemoryVersion row, boolean full) {
        return new PatientMemoryContract.VersionView(
                row.getId(), row.getPatientId(), row.getVersion() == null ? 0 : row.getVersion(),
                row.getSchemaVersion(), row.getGenerationMode(), row.getContentHash(), row.getSourceHash(),
                Json.readList(row.getSources(), Map.class).size(),
                full ? row.getMarkdown() : null,
                full ? (Map<String, Object>) Json.read(row.getData(), Map.class) : null,
                full ? (List<Map<String, Object>>) (List<?>) Json.readList(row.getSources(), Map.class) : null,
                row.getSampledAt(), row.getCreatedAt());
    }
}
