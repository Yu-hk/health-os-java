package com.kangheng.healthos.controller.patient;

import java.util.List;

import jakarta.validation.Valid;

import com.kangheng.healthos.controller.auth.CurrentUser;
import com.kangheng.healthos.contract.patient.PatientContract;
import com.kangheng.healthos.service.patient.PatientDeletionService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 删除接口。四段式：预览 → 确认 → （worker 擦除）→ 台账回读。
 *
 * <p>预览与确认分开不是仪式感：预览返回的 {@code previewToken} 与
 * {@code expectedRevision} 一起构成"你确实看过这份清单、并且档案没变"的凭据。
 */
@RestController
@RequestMapping("/api/v1/patients")
public class PatientDeletionController {

    private final PatientDeletionService deletionService;

    public PatientDeletionController(PatientDeletionService deletionService) {
        this.deletionService = deletionService;
    }

    @PostMapping("/{patientId}/deletion/previews")
    @ResponseStatus(HttpStatus.CREATED)
    public PatientContract.DeletionPreview preview(@CurrentUser String username,
                                                   @PathVariable String patientId,
                                                   @RequestBody(required = false)
                                                   PatientContract.DeletionPreviewRequest request) {
        String reason = request == null ? null : request.reason();
        return deletionService.preview(patientId, reason, username);
    }

    @GetMapping("/{patientId}/deletion/tasks")
    public List<PatientContract.DeletionTaskView> tasks(@CurrentUser String username,
                                                        @PathVariable String patientId) {
        return deletionService.list(patientId, username);
    }

    @PostMapping("/{patientId}/deletion/tasks/{taskId}/confirm")
    public PatientContract.DeletionTaskView confirm(
            @CurrentUser String username,
            @PathVariable String patientId,
            @PathVariable String taskId,
            @Valid @RequestBody PatientContract.DeletionConfirmRequest request) {
        return deletionService.confirm(patientId, taskId, request, username);
    }

    @GetMapping("/{patientId}/deletion/tasks/{taskId}")
    public PatientContract.DeletionTaskView task(@CurrentUser String username,
                                                 @PathVariable String patientId,
                                                 @PathVariable String taskId) {
        return deletionService.view(taskId, username);
    }

    /** 台账回读。返回体里的 verified=false 表示台账内容与登记哈希不一致。 */
    @GetMapping("/deletions/{taskId}/ledger")
    public PatientContract.LedgerView ledger(@CurrentUser String username,
                                             @PathVariable String taskId) {
        return deletionService.ledger(taskId, username);
    }
}
