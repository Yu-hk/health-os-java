package com.kangheng.healthos.controller.qa;

import java.net.URI;
import java.util.Map;

import jakarta.validation.Valid;

import com.kangheng.healthos.controller.auth.CurrentUser;
import com.kangheng.healthos.contract.qa.QaAuxiliaryContract;
import com.kangheng.healthos.service.qa.QaDiagnosticService;
import com.kangheng.healthos.service.qa.QaMemoService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * QA 域辅助接口：书签/备忘 + 单路 RAG 诊断。
 *
 * <p><b>所有端点都带 {@code @CurrentUser String username}</b>，触发登录校验，
 * 并把归属人一路传到服务层按 owner 过滤；越权一律 404。
 *
 * <pre>
 * POST   /api/v1/qa/messages/{messageId}/memo                    显式收藏（幂等）
 * GET    /api/v1/qa/patients/{patientId}/memos                   备忘列表（键集分页）
 * GET    /api/v1/qa/memos/{memoId}                               备忘详情
 * POST   /api/v1/qa/bindings/{bindingId}/diagnostics              提交诊断（202 + Location）
 * GET    /api/v1/qa/conversations/{conversationId}/diagnostics   诊断列表（键集分页）
 * GET    /api/v1/qa/diagnostics/{runId}                          诊断详情
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/qa")
public class QaAuxiliaryController {

    private final QaMemoService memoService;
    private final QaDiagnosticService diagnosticService;

    public QaAuxiliaryController(QaMemoService memoService,
                                 QaDiagnosticService diagnosticService) {
        this.memoService = memoService;
        this.diagnosticService = diagnosticService;
    }

    // ================================================================ 书签 / 备忘

    /**
     * 显式收藏一条助手答案。
     *
     * <p>请求体固定被忽略（无请求体或 {@code {}} 都行）：创建只由这一次点击触发，
     * 不让调用方通过请求体塞入 patientId / content 来伪造归属或正文。
     */
    @PostMapping("/messages/{messageId}/memo")
    public QaAuxiliaryContract.MemoCreated createMemo(
            @CurrentUser String username,
            @PathVariable String messageId,
            @RequestBody(required = false) Map<String, Object> ignored) {
        return memoService.create(username, messageId);
    }

    @GetMapping("/patients/{patientId}/memos")
    public QaAuxiliaryContract.MemoPage memos(
            @CurrentUser String username,
            @PathVariable String patientId,
            @RequestParam(required = false) String before,
            @RequestParam(required = false) Integer limit) {
        return memoService.list(username, patientId, before, limit);
    }

    @GetMapping("/memos/{memoId}")
    public QaAuxiliaryContract.MemoDetail memo(
            @CurrentUser String username,
            @PathVariable String memoId) {
        return memoService.detail(username, memoId);
    }

    // ================================================================ 单路 RAG 诊断

    /**
     * 提交诊断。
     *
     * <p>202 + {@code Location}：客户端凭 {@code statusUrl} / Location 轮询详情。
     * {@code Idempotency-Key} 缺失由服务层返回 422（不在这里用 {@code required=true}
     * 硬拦，那样会变成 Spring 的 400 且没有业务错误码）。
     */
    @PostMapping("/bindings/{bindingId}/diagnostics")
    public ResponseEntity<QaAuxiliaryContract.DiagnosticAccepted> createDiagnostic(
            @CurrentUser String username,
            @PathVariable String bindingId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody QaAuxiliaryContract.DiagnosticRequest request) {
        QaAuxiliaryContract.DiagnosticAccepted accepted =
                diagnosticService.submit(username, bindingId, idempotencyKey, request);
        return ResponseEntity.accepted()
                .location(URI.create(accepted.statusUrl()))
                .body(accepted);
    }

    @GetMapping("/conversations/{conversationId}/diagnostics")
    public QaAuxiliaryContract.DiagnosticPage diagnostics(
            @CurrentUser String username,
            @PathVariable String conversationId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return diagnosticService.list(username, conversationId, cursor, limit);
    }

    @GetMapping("/diagnostics/{runId}")
    public QaAuxiliaryContract.DiagnosticDetail diagnostic(
            @CurrentUser String username,
            @PathVariable String runId) {
        return diagnosticService.detail(username, runId);
    }
}
