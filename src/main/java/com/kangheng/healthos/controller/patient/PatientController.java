package com.kangheng.healthos.controller.patient;

import java.io.IOException;
import java.util.List;

import jakarta.validation.Valid;

import com.kangheng.healthos.controller.auth.CurrentUser;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.PageResult;
import com.kangheng.healthos.contract.patient.PatientContract;
import com.kangheng.healthos.service.patient.PatientLifecycleService;
import com.kangheng.healthos.service.patient.PatientObjectService;
import com.kangheng.healthos.service.patient.PatientService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 患者接口。
 *
 * <p>接口形状刻意分成三类，别混着用：
 * <ul>
 *   <li><b>建档 / 读</b>：{@code POST /patients}、{@code GET /patients/{id}}；</li>
 *   <li><b>写</b>：一律 {@code PATCH} 且必须带 {@code expectedRevision}，冲突返回 409；</li>
 *   <li><b>状态流转</b>：{@code confirm / archive / restore} 是显式动作，
 *       不做"传个 status 就改"的万能接口——那样等于把状态机交给调用方。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/patients")
public class PatientController {

    private final PatientService patientService;
    private final PatientLifecycleService lifecycle;
    private final PatientObjectService objectService;

    public PatientController(PatientService patientService,
                             PatientLifecycleService lifecycle,
                             PatientObjectService objectService) {
        this.patientService = patientService;
        this.lifecycle = lifecycle;
        this.objectService = objectService;
    }

    // ------------------------------------------------------------ 建档与读

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PatientContract.PatientView create(@CurrentUser String username,
                                              @Valid @RequestBody PatientContract.CreatePatientRequest request) {
        return lifecycle.create(request, username);
    }

    @GetMapping("/{patientId}")
    public PatientContract.PatientDetail detail(@CurrentUser String username,
                                                @PathVariable String patientId) {
        return patientService.detail(patientId, username);
    }

    @GetMapping
    public PageResult<PatientContract.PatientView> list(
            @CurrentUser String username,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return patientService.list(page, size, username);
    }

    // ------------------------------------------------------------ 编辑与状态流转

    @PatchMapping("/{patientId}")
    public PatientContract.PatientView update(@CurrentUser String username,
                                              @PathVariable String patientId,
                                              @Valid @RequestBody PatientContract.UpdatePatientRequest request) {
        return lifecycle.update(patientId, request, username);
    }

    @PostMapping("/{patientId}/confirm")
    public PatientContract.PatientView confirm(@CurrentUser String username,
                                               @PathVariable String patientId,
                                               @Valid @RequestBody PatientContract.TransitionRequest request) {
        return lifecycle.confirm(patientId, request, username);
    }

    @PostMapping("/{patientId}/archive")
    public PatientContract.PatientView archive(@CurrentUser String username,
                                               @PathVariable String patientId,
                                               @Valid @RequestBody PatientContract.TransitionRequest request) {
        return lifecycle.archive(patientId, request, username);
    }

    @PostMapping("/{patientId}/restore")
    public PatientContract.PatientView restore(@CurrentUser String username,
                                               @PathVariable String patientId,
                                               @Valid @RequestBody PatientContract.TransitionRequest request) {
        return lifecycle.restore(patientId, request, username);
    }

    // ------------------------------------------------------------ 版本

    @GetMapping("/{patientId}/revisions")
    public List<PatientContract.RevisionView> revisions(@CurrentUser String username,
                                                        @PathVariable String patientId) {
        return lifecycle.revisions(patientId, username);
    }

    @GetMapping("/{patientId}/revisions/{revision}")
    public Object revisionSnapshot(@CurrentUser String username,
                                   @PathVariable String patientId,
                                   @PathVariable int revision) {
        return lifecycle.revisionSnapshot(patientId, revision, username);
    }

    // ------------------------------------------------------------ 指标

    @PostMapping("/{patientId}/metrics")
    public PatientContract.PatientDetail upsertMetrics(
            @CurrentUser String username,
            @PathVariable String patientId,
            @Valid @RequestBody List<PatientContract.MetricInput> metrics) {
        return patientService.upsertMetrics(patientId, metrics, username);
    }

    // ------------------------------------------------------------ 附件

    @PostMapping(value = "/{patientId}/objects", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public PatientContract.ObjectView uploadObject(@CurrentUser String username,
                                                   @PathVariable String patientId,
                                                   @RequestParam("file") MultipartFile file,
                                                   @RequestParam(value = "kind", required = false) String kind) {
        try {
            return objectService.upload(patientId, file.getOriginalFilename(), file.getContentType(),
                    kind, file.getBytes(), username);
        } catch (IOException ex) {
            throw BizException.badRequest("读取上传文件失败: " + ex.getMessage());
        }
    }

    @GetMapping("/{patientId}/objects")
    public List<PatientContract.ObjectView> objects(@CurrentUser String username,
                                                    @PathVariable String patientId) {
        return objectService.list(patientId, username);
    }

    @DeleteMapping("/{patientId}/objects/{objectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteObject(@CurrentUser String username,
                             @PathVariable String patientId,
                             @PathVariable String objectId) {
        objectService.delete(patientId, objectId, username);
    }
}
