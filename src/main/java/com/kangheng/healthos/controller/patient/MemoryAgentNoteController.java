package com.kangheng.healthos.controller.patient;

import jakarta.validation.Valid;

import com.kangheng.healthos.controller.auth.CurrentUser;
import com.kangheng.healthos.contract.patient.MemoryAgentNoteContract;
import com.kangheng.healthos.service.patient.MemoryAgentNoteService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 记忆 Agent 摘录接口。
 *
 * <pre>
 * POST /api/v1/patients/{patientId}/memory/agent-followups                 生成候选（202，PENDING）
 * GET  /api/v1/patients/{patientId}/memory/agent-notes                     候选列表
 * POST /api/v1/patients/{patientId}/memory/agent-notes/{noteId}/publish     显式发布
 * </pre>
 *
 * <p>与既有 PatientMemoryController 挂在同一前缀下，路径段互不重叠。候选落 PENDING
 * 不自动写记忆版本；发布在 note 表内完成（不改正式记忆版本，凭据落 note 表）。
 * 所有端点都带 {@code @CurrentUser String username} 触发登录校验，按 owner 隔离（查不到 404）。
 */
@RestController
@RequestMapping("/api/v1/patients/{patientId}/memory")
public class MemoryAgentNoteController {

    private final MemoryAgentNoteService noteService;

    public MemoryAgentNoteController(MemoryAgentNoteService noteService) {
        this.noteService = noteService;
    }

    /** 202：候选已受理为 PENDING，不会自动发布记忆版本（对齐原工程"候选由独立 worker 发布"）。 */
    @PostMapping("/agent-followups")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MemoryAgentNoteContract.NoteView agentFollowup(
            @CurrentUser String username,
            @PathVariable String patientId,
            @Valid @RequestBody MemoryAgentNoteContract.AgentFollowupRequest request) {
        return noteService.submit(username, patientId, request);
    }

    @GetMapping("/agent-notes")
    public MemoryAgentNoteContract.NoteListView agentNotes(@CurrentUser String username,
                                                           @PathVariable String patientId) {
        return noteService.list(username, patientId);
    }

    @PostMapping("/agent-notes/{noteId}/publish")
    public MemoryAgentNoteContract.NoteView publish(
            @CurrentUser String username,
            @PathVariable String patientId,
            @PathVariable String noteId,
            @Valid @RequestBody MemoryAgentNoteContract.PublishRequest request) {
        return noteService.publish(username, patientId, noteId, request);
    }
}
