package com.kangheng.healthos.controller.patient;

import java.nio.charset.StandardCharsets;

import jakarta.validation.Valid;

import com.kangheng.healthos.common.PageResult;
import com.kangheng.healthos.controller.auth.CurrentUser;
import com.kangheng.healthos.contract.patient.PatientMemoryContract;
import com.kangheng.healthos.service.patient.PatientMemoryJobService;
import com.kangheng.healthos.service.patient.PatientMemoryService;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 患者长效记忆接口。
 *
 * <p>对齐原工程 {@code memory_api} 的 {@code /api/v2/patient-memory} 一族，
 * 路径挂到 {@code /api/v1/patients/{patientId}/memory}（本工程所有患者资源都在 v1 的患者路径下）。
 *
 * <pre>
 * GET    /memory                                    当前版本
 * POST   /memory/initialize                        初始化（幂等）
 * GET    /memory/versions                          版本列表
 * GET    /memory/versions/{memoryId}                版本详情
 * GET    /memory/versions/{memoryId}/export         Markdown 导出（带内容哈希）
 * POST   /memory/followups                         随访提交（202）
 * GET    /memory/followups                         随访列表
 * POST   /memory/refresh                           显式刷新（202）
 * GET    /memory/jobs/{jobId}                      任务详情
 * POST   /memory/jobs/{jobId}/retry|cancel         重试 / 取消
 * </pre>
 *
 * <p>所有响应都是 {@code private, no-store}：长效记忆含患者身份与自述内容，
 * 不该被浏览器或任何中间代理缓存。
 */
@RestController
@RequestMapping("/api/v1/patients/{patientId}/memory")
public class PatientMemoryController {

    private final PatientMemoryService memoryService;
    private final PatientMemoryJobService jobService;

    public PatientMemoryController(PatientMemoryService memoryService,
                                   PatientMemoryJobService jobService) {
        this.memoryService = memoryService;
        this.jobService = jobService;
    }

    @GetMapping
    public PatientMemoryContract.VersionView current(@CurrentUser String username,
                                                     @PathVariable String patientId) {
        return memoryService.current(username, patientId);
    }

    @PostMapping("/initialize")
    public PatientMemoryContract.VersionView initialize(@CurrentUser String username,
                                                       @PathVariable String patientId) {
        return memoryService.initialize(username, patientId);
    }

    @GetMapping("/versions")
    public PageResult<PatientMemoryContract.VersionSummary> versions(
            @CurrentUser String username,
            @PathVariable String patientId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return memoryService.versions(username, patientId, page, size);
    }

    @GetMapping("/versions/{memoryId}")
    public PatientMemoryContract.VersionView version(@CurrentUser String username,
                                                     @PathVariable String patientId,
                                                     @PathVariable String memoryId) {
        return memoryService.version(username, patientId, memoryId);
    }

    /**
     * 导出 Markdown。{@code X-Content-SHA256} 是版本里记录的哈希，
     * 客户端可据此校验文件没被改过——不是响应时现算的（现算只能证明文件自洽，
     * 证明不了数据库里那份没被替换）。
     */
    @GetMapping("/versions/{memoryId}/export")
    public ResponseEntity<byte[]> export(@CurrentUser String username,
                                         @PathVariable String patientId,
                                         @PathVariable String memoryId) {
        PatientMemoryService.ExportFile file = memoryService.export(username, patientId, memoryId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-SHA256", file.contentHash())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.content().length)
                .body(file.content());
    }

    @PostMapping("/followups")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PatientMemoryContract.Accepted followup(
            @CurrentUser String username,
            @PathVariable String patientId,
            @Valid @RequestBody PatientMemoryContract.FollowupRequest request) {
        return jobService.submitFollowup(username, patientId, request);
    }

    @GetMapping("/followups")
    public PageResult<PatientMemoryContract.FollowupView> followups(
            @CurrentUser String username,
            @PathVariable String patientId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return jobService.followups(username, patientId, page, size);
    }

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PatientMemoryContract.Accepted refresh(
            @CurrentUser String username,
            @PathVariable String patientId,
            @Valid @RequestBody PatientMemoryContract.MemoryRequest request) {
        return jobService.submitRefresh(username, patientId, request);
    }

    @GetMapping("/jobs/{jobId}")
    public PatientMemoryContract.JobView job(@CurrentUser String username,
                                             @PathVariable String patientId,
                                             @PathVariable String jobId) {
        return jobService.job(username, patientId, jobId);
    }

    @PostMapping("/jobs/{jobId}/retry")
    public PatientMemoryContract.JobView retry(@CurrentUser String username,
                                               @PathVariable String patientId,
                                               @PathVariable String jobId) {
        return jobService.retry(username, patientId, jobId);
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public PatientMemoryContract.JobView cancel(@CurrentUser String username,
                                                @PathVariable String patientId,
                                                @PathVariable String jobId) {
        return jobService.cancel(username, patientId, jobId);
    }
}
