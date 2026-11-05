package com.kangheng.healthos.controller.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract.AgentScope;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract.AgentTool;
import com.kangheng.healthos.contract.agent.AgentLabContract;
import com.kangheng.healthos.service.agent.AgentLabToolService;
import com.kangheng.healthos.spi.agent.McpToolGrants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部只读工具端点：给 {@code stdio} 形态的 MCP 子进程回调用。
 *
 * <pre>
 * POST /internal/agent-lab/tools/{tool}
 *   X-Internal-Token: &lt;令牌&gt;
 *   { "grant": "mgrant_...", "arguments": { } }
 *   → 200 { "structuredContent": { ... } }
 * </pre>
 *
 * <p><b>它为什么不走登录态</b>：调用方是一个本地子进程，没有用户会话。身份靠三件事还原：
 * <ol>
 *   <li><b>只认回环地址</b>：{@code getRemoteAddr()} 必须是 127.0.0.1/::1。
 *       这条挡的是"内网另一台机器直接打过来"，与令牌互为兜底；</li>
 *   <li><b>内部令牌</b>：常量时间比较，且配置为空时直接拒绝（不是"空对空放行"）；</li>
 *   <li><b>短命凭证</b>：{@code grant} 由父进程在发起这次操作时按登录态签发，
 *       子进程只负责回传。<b>请求体里没有 owner/patientId</b>，子进程也就无从指定身份。</li>
 * </ol>
 *
 * <p>响应统一包一层 {@code structuredContent}：子进程原样转成 MCP 的
 * {@code CallToolResult.structuredContent}，两边不需要再约定第二套结构。
 *
 * <p>这个路径<b>不该</b>出现在对外网关里：它带着"免登录读患者数据"的能力，
 * 一旦被映射到公网，令牌就成了最后一道门。
 */
@RestController
@RequestMapping("/internal/agent-lab/tools")
public class InternalAgentToolController {

    private static final Logger log = LoggerFactory.getLogger(InternalAgentToolController.class);
    private static final String HEADER = "X-Internal-Token";

    private final AgentLabToolService toolService;
    private final McpToolGrants grants;
    private final HealthOsProperties.AgentLab config;

    public InternalAgentToolController(AgentLabToolService toolService,
                                       McpToolGrants grants,
                                       HealthOsProperties properties) {
        this.toolService = toolService;
        this.grants = grants;
        this.config = properties.getAgentLab();
    }

    @PostMapping("/{tool}")
    public ResponseEntity<Map<String, Object>> invoke(@PathVariable String tool,
                                                      @RequestHeader(value = HEADER, required = false) String token,
                                                      @RequestBody(required = false) Map<String, Object> body,
                                                      HttpServletRequest request) {
        if (!fromLoopback(request)) {
            log.warn("内部工具端点拒绝了非回环调用 tool={} remote={}", tool, request.getRemoteAddr());
            return error(HttpStatus.FORBIDDEN, "INTERNAL_ONLY", "该端点只接受回环调用");
        }
        if (!tokenMatches(token)) {
            log.warn("内部工具端点拒绝了令牌校验 tool={}", tool);
            return error(HttpStatus.FORBIDDEN, "INTERNAL_TOKEN_INVALID", "内部令牌无效");
        }
        Object grantId = body == null ? null : body.get("grant");
        McpToolGrants.Grant grant = grants.resolve(grantId instanceof String text ? text : null);
        if (grant == null) {
            // 过期、伪造、或压根没带：三种情况对外都是同一句话，不给探测留信息。
            log.warn("内部工具端点收到无效凭证 tool={}", tool);
            return error(HttpStatus.FORBIDDEN, "INTERNAL_GRANT_INVALID", "凭证无效或已过期");
        }
        Set<String> allowed = AgentLabContract.authorizedToolWires(scopesOf(grant));
        if (!allowed.contains(tool)) {
            log.warn("内部工具端点拒绝了越权工具 tool={}", tool);
            return error(HttpStatus.FORBIDDEN, "MCP_TOOL_REJECTED", "该工具不在本次授权范围内");
        }
        Map<String, Object> arguments = AgentLabToolService.sanitizeArguments(
                body.get("arguments") instanceof Map<?, ?> raw ? castMap(raw) : null);
        Map<String, Object> structured;
        try {
            structured = toolService.execute(AgentTool.of(tool), grant.owner(), grant.patientId(), arguments);
        } catch (RuntimeException ex) {
            // 与回执侧同样的原则：不把堆栈/SQL 回给子进程，它会原样写进 MCP 的 content.text。
            log.warn("内部工具端点执行失败 tool={}", tool);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, AgentLabContract.MCP_UNAVAILABLE, "工具执行失败");
        }
        if (structured == null || structured.isEmpty()) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, AgentLabContract.MCP_OUTPUT_INVALID, "工具输出为空");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("structuredContent", structured);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(payload);
    }

    private static Set<AgentScope> scopesOf(McpToolGrants.Grant grant) {
        Set<AgentScope> scopes = new java.util.LinkedHashSet<>();
        for (String wire : grant.scopeWires()) {
            AgentScope scope = AgentScope.of(wire);
            if (scope != null) {
                scopes.add(scope);
            }
        }
        return scopes;
    }

    private static Map<String, Object> castMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static boolean fromLoopback(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return "127.0.0.1".equals(remote) || "::1".equals(remote)
                || "0:0:0:0:0:0:0:1".equals(remote);
    }

    /**
     * 常量时间比较。
     *
     * <p>配置为空时返回 false，而不是"两边都空就算通过"：忘配令牌不该变成免鉴权。
     */
    private boolean tokenMatches(String token) {
        String expected = config.getInternalToken();
        if (!StringUtils.hasText(expected) || !StringUtils.hasText(token)) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status,
                                                             String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(body);
    }
}
