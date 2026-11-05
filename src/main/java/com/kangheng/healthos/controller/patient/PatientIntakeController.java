package com.kangheng.healthos.controller.patient;

import java.io.IOException;

import jakarta.validation.Valid;

import com.kangheng.healthos.controller.auth.CurrentUser;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.PageResult;
import com.kangheng.healthos.contract.patient.PatientContract;
import com.kangheng.healthos.service.patient.PatientIntakeService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 资料录入接口。
 *
 * <p>链路：上传（202，异步抽取）→ 查详情 → 人工核查两道 → 确认生成档案。
 * 抽取是异步的，所以上传接口返回的是 {@code PENDING} 而不是结果——
 * 前端要轮询 {@code GET /intakes/{id}} 看 {@code status} 变成 {@code EXTRACTED}。
 */
@RestController
@RequestMapping("/api/v1/patient-intakes")
public class PatientIntakeController {

    private final PatientIntakeService intakeService;

    public PatientIntakeController(PatientIntakeService intakeService) {
        this.intakeService = intakeService;
    }

    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PatientContract.IntakeSubmitResponse submit(@CurrentUser String username,
                                                        @RequestParam("file") MultipartFile file) {
        try {
            return intakeService.submit(file.getOriginalFilename(), file.getContentType(),
                    file.getBytes(), username);
        } catch (IOException ex) {
            throw BizException.badRequest("读取上传文件失败: " + ex.getMessage());
        }
    }

    @GetMapping
    public PageResult<PatientContract.IntakeView> list(
            @CurrentUser String username,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return intakeService.list(username, page, size);
    }

    @GetMapping("/{examId}")
    public PatientContract.IntakeView detail(@CurrentUser String username,
                                             @PathVariable String examId) {
        return intakeService.detail(examId, username);
    }

    /** 人工核查：基础信息 / 临床内容两道，可分别表态。 */
    @PostMapping("/{examId}/reviews")
    public PatientContract.IntakeView review(@CurrentUser String username,
                                             @PathVariable String examId,
                                             @Valid @RequestBody PatientContract.IntakeReviewRequest request) {
        return intakeService.review(examId, request, username);
    }

    /** 两道核查都通过后，生成正式患者档案。 */
    @PostMapping("/{examId}/confirm")
    public PatientContract.IntakeConfirmResult confirm(
            @CurrentUser String username,
            @PathVariable String examId,
            @Valid @RequestBody PatientContract.IntakeConfirmRequest request) {
        return intakeService.confirm(examId, request, username);
    }
}
