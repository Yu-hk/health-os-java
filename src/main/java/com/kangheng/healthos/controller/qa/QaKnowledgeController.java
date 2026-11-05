package com.kangheng.healthos.controller.qa;

import jakarta.validation.Valid;

import com.kangheng.healthos.controller.auth.CurrentUser;
import com.kangheng.healthos.contract.qa.KnowledgeBindingContract;
import com.kangheng.healthos.service.qa.KnowledgeBindingService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 患者知识库绑定接口。
 *
 * <p>对齐原工程 {@code qa_api} 的 {@code /patients/{id}/rag-bindings} 一族，
 * 路径改成 {@code knowledge-bases}（{@code rag-bindings} 里的 "rag" 是引擎名，
 * 换实现就得改 URL）。
 *
 * <pre>
 * GET    /api/v1/qa/patients/{patientId}/knowledge-bases                    已绑 + 可绑
 * POST   /api/v1/qa/patients/{patientId}/knowledge-bases                    绑定（幂等）
 * DELETE /api/v1/qa/patients/{patientId}/knowledge-bases/{bindingId}        解绑（不删库）
 * POST   /api/v1/qa/patients/{patientId}/knowledge-bases/{bindingId}/reconcile  对账
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/qa/patients/{patientId}/knowledge-bases")
public class QaKnowledgeController {

    private final KnowledgeBindingService bindingService;

    public QaKnowledgeController(KnowledgeBindingService bindingService) {
        this.bindingService = bindingService;
    }

    @GetMapping
    public KnowledgeBindingContract.Overview overview(@CurrentUser String username,
                                                      @PathVariable String patientId) {
        return bindingService.overview(username, patientId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeBindingContract.BindingView bind(
            @CurrentUser String username,
            @PathVariable String patientId,
            @Valid @RequestBody KnowledgeBindingContract.BindRequest request) {
        return bindingService.bind(username, patientId, request);
    }

    @DeleteMapping("/{bindingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unbind(@CurrentUser String username,
                       @PathVariable String patientId,
                       @PathVariable String bindingId) {
        bindingService.unbind(username, patientId, bindingId);
    }

    @PostMapping("/{bindingId}/reconcile")
    public KnowledgeBindingContract.BindingView reconcile(@CurrentUser String username,
                                                          @PathVariable String patientId,
                                                          @PathVariable String bindingId) {
        return bindingService.reconcile(username, patientId, bindingId);
    }
}
