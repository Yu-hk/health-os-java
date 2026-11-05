package com.kangheng.healthos.controller.agent;

import jakarta.validation.Valid;

import com.kangheng.healthos.controller.auth.CurrentUser;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract;
import com.kangheng.healthos.service.agent.AgentCollaborationService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多智能体协同接口。
 *
 * <pre>
 * POST /api/v1/patients/{patientId}/collaborations   提交协同（异步，返回 202 + runId）
 * GET  /api/v1/collaborations/{runId}                 查询运行状态与节点
 * </pre>
 *
 * <p>提交是异步的：一次协同要读记忆、报告、计划、任务状态，还要跑四个角色，
 * 同步等待会把请求线程占住十几秒。提交后拿 {@code runId} 轮询即可。
 */
@RestController
@RequestMapping("/api/v1")
public class AgentCollaborationController {

    private final AgentCollaborationService collaborationService;

    public AgentCollaborationController(AgentCollaborationService collaborationService) {
        this.collaborationService = collaborationService;
    }

    /** 提交一次协同。 */
    @PostMapping("/patients/{patientId}/collaborations")
    public ResponseEntity<AgentCollaborationContract.CollaborationAccepted> submit(
            @CurrentUser String username,
            @PathVariable String patientId,
            @Valid @RequestBody AgentCollaborationContract.SubmitCollaborationRequest request) {
        AgentCollaborationContract.CollaborationAccepted accepted =
                collaborationService.submit(username, patientId, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
    }

    /** 查询运行状态与节点。 */
    @GetMapping("/collaborations/{runId}")
    public ResponseEntity<AgentCollaborationContract.CollaborationRunView> get(
            @CurrentUser String username,
            @PathVariable String runId) {
        return ResponseEntity.ok(collaborationService.get(username, runId));
    }
}
