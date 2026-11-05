package com.kangheng.healthos.controller.patient;

import jakarta.validation.Valid;

import com.kangheng.healthos.controller.auth.CurrentUser;
import com.kangheng.healthos.contract.patient.PatientDraftContract;
import com.kangheng.healthos.service.patient.PatientDraftService;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 患者资料录入审阅草稿接口。
 *
 * <pre>
 * GET    /api/v1/patient-intakes/exams/{examId}/drafts/{scope}   读草稿（无则 draft:null）
 * PUT    /api/v1/patient-intakes/exams/{examId}/drafts/{scope}   写草稿（乐观锁 / 幂等）
 * DELETE /api/v1/patient-intakes/exams/{examId}/drafts/{scope}   删草稿（幂等）
 * </pre>
 *
 * <p>路径里多一段 {@code exams} 是为了避开既有 PatientIntakeController 的
 * {@code /api/v1/patient-intakes/{examId}}，两者不冲突。
 *
 * <p><b>草稿没有提交/确认端点</b>：它绝不改动临床修订与确认状态，
 * 正式写入走既有的 {@code /exams/{examId}/confirm}。所有端点都带
 * {@code @CurrentUser String username} 触发登录校验，并按 owner 隔离（查不到 404）。
 */
@RestController
@RequestMapping("/api/v1/patient-intakes")
public class PatientDraftController {

    private final PatientDraftService draftService;

    public PatientDraftController(PatientDraftService draftService) {
        this.draftService = draftService;
    }

    @GetMapping("/exams/{examId}/drafts/{scope}")
    public PatientDraftContract.DraftResponse get(@CurrentUser String username,
                                                  @PathVariable String examId,
                                                  @PathVariable String scope) {
        return draftService.get(examId, scope, username);
    }

    @PutMapping("/exams/{examId}/drafts/{scope}")
    public PatientDraftContract.DraftResponse put(
            @CurrentUser String username,
            @PathVariable String examId,
            @PathVariable String scope,
            @Valid @RequestBody PatientDraftContract.PutDraftRequest request) {
        return draftService.put(examId, scope, request, username);
    }

    @DeleteMapping("/exams/{examId}/drafts/{scope}")
    public PatientDraftContract.DraftResponse delete(@CurrentUser String username,
                                                     @PathVariable String examId,
                                                     @PathVariable String scope) {
        return draftService.delete(examId, scope, username);
    }
}
