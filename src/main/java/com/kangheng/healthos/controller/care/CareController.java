package com.kangheng.healthos.controller.care;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import com.kangheng.healthos.controller.auth.CurrentUser;
import com.kangheng.healthos.contract.care.CareContract;
import com.kangheng.healthos.service.care.CareExportService;
import com.kangheng.healthos.service.care.CareService;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康管理接口。
 *
 * <p>路径与原工程 {@code care_api} 对齐（去掉 {@code /v2} 前缀）：
 * <pre>
 * POST   /api/v1/care/reports                          创建报告（异步生成）
 * GET    /api/v1/care/reports                          报告列表
 * GET    /api/v1/care/plans                            计划列表
 * GET    /api/v1/care/documents/{id}                   文档详情
 * PATCH  /api/v1/care/documents/{id}/sections/{key}    编辑分节
 * POST   /api/v1/care/documents/{id}/sign              签发报告
 * POST   /api/v1/care/documents/{id}/revise            创建修订草稿
 * POST   /api/v1/care/documents/{id}/confirm           确认计划生效
 * PUT    /api/v1/care/documents/{id}/checkins/{taskId}  打卡
 * GET    /api/v1/care/documents/{id}/revisions         修订历史
 * GET    /api/v1/care/documents/{id}/audits            审计流水
 * GET    /api/v1/care/documents/{id}/export            导出 JSON（未完成 409）
 * GET    /api/v1/care/documents/{id}/pdf               导出 PDF（缺字体 503）
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/care")
public class CareController {

    private final CareService careService;
    private final CareExportService careExportService;

    public CareController(CareService careService, CareExportService careExportService) {
        this.careService = careService;
        this.careExportService = careExportService;
    }

    /** 分节目录，供前端渲染编辑器时自描述可用分节。 */
    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        return Map.of(
                "reportKeys", CareContract.REPORT_KEYS,
                "planKeys", CareContract.PLAN_KEYS,
                "sectionTitles", CareContract.SECTION_TITLES,
                "disclaimer", CareContract.DISCLAIMER);
    }

    @PostMapping("/reports")
    public CareContract.AcceptedResponse createReport(
            @CurrentUser String username,
            @Valid @RequestBody CareContract.CreateReportRequest request) {
        return careService.createReport(username, request);
    }

    @GetMapping("/reports")
    public List<CareContract.DocumentView> reports(@CurrentUser String username,
                                                   @RequestParam(required = false) String patientId,
                                                   @RequestParam(defaultValue = "100") int limit) {
        return careService.list(username, CareService.KIND_REPORT, patientId, limit);
    }

    @GetMapping("/plans")
    public List<CareContract.DocumentView> plans(@CurrentUser String username,
                                                 @RequestParam(required = false) String patientId,
                                                 @RequestParam(defaultValue = "100") int limit) {
        return careService.list(username, CareService.KIND_PLAN, patientId, limit);
    }

    @GetMapping("/documents/{documentId}")
    public CareContract.DocumentView document(@CurrentUser String username,
                                              @PathVariable String documentId) {
        return careService.get(username, documentId);
    }

    @PatchMapping("/documents/{documentId}/sections/{key}")
    public CareContract.DocumentView editSection(@CurrentUser String username,
                                                  @PathVariable String documentId,
                                                  @PathVariable String key,
                                                  @Valid @RequestBody CareContract.EditSectionRequest request) {
        return careService.editSection(username, documentId, key, request);
    }

    @PostMapping("/documents/{documentId}/sign")
    public CareContract.DocumentView sign(@CurrentUser String username,
                                          @PathVariable String documentId,
                                          @Valid @RequestBody CareContract.SignRequest request) {
        return careService.sign(username, documentId, request);
    }

    @PostMapping("/documents/{documentId}/revise")
    public CareContract.AcceptedResponse revise(@CurrentUser String username,
                                                @PathVariable String documentId,
                                                @Valid @RequestBody CareContract.ReviseRequest request) {
        return careService.revise(username, documentId, request);
    }

    @PostMapping("/documents/{documentId}/confirm")
    public CareContract.DocumentView confirmPlan(@CurrentUser String username,
                                                  @PathVariable String documentId,
                                                  @Valid @RequestBody CareContract.ConfirmPlanRequest request) {
        return careService.confirmPlan(username, documentId, request);
    }

    @PutMapping("/documents/{documentId}/checkins/{taskId}")
    public CareContract.DocumentView checkin(@CurrentUser String username,
                                             @PathVariable String documentId,
                                             @PathVariable String taskId,
                                             @Valid @RequestBody CareContract.CheckinRequest request) {
        return careService.checkin(username, documentId, taskId, request);
    }

    @GetMapping("/documents/{documentId}/revisions")
    public List<CareContract.RevisionView> revisions(@CurrentUser String username,
                                                      @PathVariable String documentId) {
        return careService.revisions(username, documentId);
    }

    @GetMapping("/documents/{documentId}/audits")
    public List<CareContract.AuditView> audits(@CurrentUser String username,
                                               @PathVariable String documentId) {
        return careService.audits(username, documentId);
    }

    /**
     * 导出 JSON。
     *
     * <p>用附件形式下发（{@code Content-Disposition: attachment}）而不是直接返回 JSON：
     * 导出是"拿走一份文件"，不是"查询一个资源"。
     */
    @GetMapping("/documents/{documentId}/export")
    public ResponseEntity<byte[]> export(@CurrentUser String username,
                                         @PathVariable String documentId) {
        return attachment(careExportService.exportJson(username, documentId));
    }

    /** 导出 PDF。字体不可用时 503，不返回没有汉字的半成品。 */
    @GetMapping("/documents/{documentId}/pdf")
    public ResponseEntity<byte[]> pdf(@CurrentUser String username,
                                      @PathVariable String documentId) {
        return attachment(careExportService.renderPdf(username, documentId));
    }

    private ResponseEntity<byte[]> attachment(CareExportService.ExportFile file) {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                // 医疗内容不能被任何中间层缓存。
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.content().length)
                .body(file.content());
    }
}
