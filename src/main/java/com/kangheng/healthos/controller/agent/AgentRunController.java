package com.kangheng.healthos.controller.agent;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import com.kangheng.healthos.controller.auth.CurrentUser;
import com.kangheng.healthos.contract.agent.AgentRunContract;
import com.kangheng.healthos.service.agent.AgentRunControlService;
import com.kangheng.healthos.service.agent.AgentRunEventService;
import com.kangheng.healthos.service.agent.AgentRunStreamService;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent Run 事件流与控制面。
 *
 * <pre>
 * GET  /api/v1/collaborations                              运行列表（键集分页）
 * GET  /api/v1/collaborations/{runId}/events             增量事件（轮询主通道）
 * GET  /api/v1/collaborations/{runId}/stream             事件流（SSE，同一份事件）
 * POST /api/v1/collaborations/{runId}/cancel             取消（幂等）
 * POST /api/v1/collaborations/{runId}/retry              重试失败运行（幂等）
 * GET  /api/v1/collaborations/{runId}/nodes/{nodeId}     节点正文（默认脱敏）
 * </pre>
 *
 * <p>全部响应 {@code Cache-Control: no-store}：协同内容含患者事实与结论，
 * 不能被浏览器或中间代理缓存成「别人的上一条结果」。
 */
@RestController
@RequestMapping("/api/v1")
public class AgentRunController {

    private final AgentRunControlService controlService;
    private final AgentRunEventService eventService;
    private final AgentRunStreamService streamService;

    public AgentRunController(AgentRunControlService controlService,
                              AgentRunEventService eventService,
                              AgentRunStreamService streamService) {
        this.controlService = controlService;
        this.eventService = eventService;
        this.streamService = streamService;
    }

    /** 运行列表。 */
    @GetMapping("/collaborations")
    public ResponseEntity<AgentRunContract.RunPage> page(
            @CurrentUser String username,
            @RequestParam(required = false) String patientId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String beforeId) {
        return noStore(controlService.page(username, patientId, limit, beforeId));
    }

    /** 增量事件。afterSeq=0 可重放全量历史。 */
    @GetMapping("/collaborations/{runId}/events")
    public ResponseEntity<AgentRunContract.EventPage> events(
            @CurrentUser String username,
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") int afterSeq,
            @RequestParam(defaultValue = "100") int limit) {
        // 轮询与 SSE 走同一个查询方法：limit 的 clamp、nextSeq 的口径只有一份实现。
        return noStore(eventService.events(username, runId, afterSeq, limit));
    }

    /** SSE：断线重连带 afterSeq 即可补齐。 */
    @GetMapping(value = "/collaborations/{runId}/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@CurrentUser String username,
                             @PathVariable String runId,
                             @RequestParam(defaultValue = "0") int afterSeq,
                             HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return streamService.subscribe(username, runId, afterSeq);
    }

    /** 取消。请求体允许为空（兼容原工程的 {@code {}} 语义）。 */
    @PostMapping("/collaborations/{runId}/cancel")
    public ResponseEntity<AgentRunContract.ControlView> cancel(
            @CurrentUser String username,
            @PathVariable String runId,
            @RequestBody(required = false) @Valid AgentRunContract.CancelRequest request) {
        Integer expected = request == null ? null : request.expectedVersion();
        return noStore(controlService.cancel(username, runId, expected));
    }

    /** 重试。 */
    @PostMapping("/collaborations/{runId}/retry")
    public ResponseEntity<AgentRunContract.ControlView> retry(
            @CurrentUser String username,
            @PathVariable String runId,
            @RequestBody(required = false) @Valid AgentRunContract.RetryRequest request) {
        Integer expected = request == null ? null : request.expectedVersion();
        return noStore(controlService.retry(username, runId, expected));
    }

    /** 节点正文。mode=full 需要全局读权限且在 7 天保留期内。 */
    @GetMapping("/collaborations/{runId}/nodes/{nodeId}")
    public ResponseEntity<AgentRunContract.NodePayloadView> nodePayload(
            @CurrentUser String username,
            @PathVariable String runId,
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "redacted") String mode) {
        return noStore(controlService.nodePayload(username, runId, nodeId, mode));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }
}
