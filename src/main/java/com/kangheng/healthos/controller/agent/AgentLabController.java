package com.kangheng.healthos.controller.agent;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import com.kangheng.healthos.controller.auth.CurrentUser;
import com.kangheng.healthos.contract.agent.AgentLabContract;
import com.kangheng.healthos.service.agent.AgentLabService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent Lab 工具目录与协议校验接口。
 *
 * <pre>
 * GET  /api/v1/agent-lab/catalog                          工具目录 + 指标 + 服务状态
 * POST /api/v1/agent-lab/services/{serviceId}/probe      三步协议里的前两步（握手/发现）
 * POST /api/v1/agent-lab/services/{serviceId}/call       三步协议 + 一次真实工具调用
 * </pre>
 *
 * <p><b>为什么全用 POST 做 probe/call</b>：两者都会写回执、会改变"最近一次探测"状态，
 * 不是幂等读取。catalog 是 GET，但同样禁止缓存。
 *
 * <p><b>所有响应都要 {@code Cache-Control: no-store}</b>：请求/响应里带 patientId
 * 这类患者标识，被浏览器或中间代理缓存住就等于把它写到磁盘上。因此不只在成功
 * ResponseEntity 上设置，也在原始响应上先设一份——这样走 GlobalExceptionHandler
 * 的 404/400 错误响应同样带 no-store。
 *
 * <p>{@code @CurrentUser String username} 触发登录校验，并作为全部患者域查询的 owner。
 */
@RestController
@RequestMapping("/api/v1/agent-lab")
public class AgentLabController {

    private final AgentLabService agentLabService;

    public AgentLabController(AgentLabService agentLabService) {
        this.agentLabService = agentLabService;
    }

    /** 工具目录。 */
    @GetMapping("/catalog")
    public ResponseEntity<AgentLabContract.CatalogView> catalog(
            @CurrentUser String username, HttpServletResponse response) {
        return noStore(response, agentLabService.catalog(username));
    }

    /** 服务探测：initialize → tools/list，不发 tools/call，output 为 null。 */
    @PostMapping("/services/{serviceId}/probe")
    public ResponseEntity<AgentLabContract.OperationView> probe(
            @CurrentUser String username,
            @PathVariable String serviceId,
            @Valid @RequestBody AgentLabContract.ProbeRequest request,
            HttpServletResponse response) {
        return noStore(response, agentLabService.probe(username, serviceId, request));
    }

    /** 工具调用：三步协议 + 一次 tools/call。 */
    @PostMapping("/services/{serviceId}/call")
    public ResponseEntity<AgentLabContract.OperationView> call(
            @CurrentUser String username,
            @PathVariable String serviceId,
            @Valid @RequestBody AgentLabContract.CallRequest request,
            HttpServletResponse response) {
        return noStore(response, agentLabService.call(username, serviceId, request));
    }

    /** 一律禁止缓存：原始响应与成功 ResponseEntity 都设置，错误响应也不漏。 */
    private <T> ResponseEntity<T> noStore(HttpServletResponse response, T body) {
        response.setHeader("Cache-Control", "no-store");
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }
}
