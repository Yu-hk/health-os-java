package com.kangheng.healthos.controller.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract.AgentTool;
import com.kangheng.healthos.service.agent.AgentLabToolService;
import com.kangheng.healthos.provider.agent.InMemoryMcpToolGrants;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 内部工具端点的三道门：回环、令牌、凭证。
 *
 * <p>这三条任何一条失效，"免登录读患者数据"的口子就开了，所以每条都单独钉一个用例。
 * 另外两条是"越权工具"与"失败不外泄堆栈"——前者防子进程被改坏，后者防 SQL/连接串
 * 顺着 MCP 的 {@code content.text} 回到调用方。
 */
class InternalAgentToolControllerTest {

    private static final String TOKEN = "internal-token-for-test";
    private static final String OWNER = "admin";
    private static final String PATIENT = "pat_1";

    private AgentLabToolService toolService;
    private InMemoryMcpToolGrants grants;
    private HealthOsProperties.AgentLab config;
    private InternalAgentToolController controller;

    @BeforeEach
    void setUp() {
        toolService = mock(AgentLabToolService.class);
        grants = new InMemoryMcpToolGrants(Duration.ofMinutes(1));
        HealthOsProperties properties = new HealthOsProperties();
        config = properties.getAgentLab();
        config.setInternalToken(TOKEN);
        controller = new InternalAgentToolController(toolService, grants, properties);
    }

    // ------------------------------------------------------------ 三道门

    @Test
    @DisplayName("非回环来源一律 403，且不查凭证不查库")
    void rejectsNonLoopback() {
        String grant = grants.issue(OWNER, PATIENT, Set.of("memory"));
        ResponseEntity<Map<String, Object>> response = controller.invoke("read_patient_memory",
                TOKEN, body(grant, Map.of()), request("10.1.2.3"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "INTERNAL_ONLY");
        verify(toolService, never()).execute(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("令牌不符或缺失 403；配置为空时一律拒绝（不做空对空放行）")
    void rejectsBadToken() {
        String grant = grants.issue(OWNER, PATIENT, Set.of("memory"));

        assertThat(controller.invoke("read_patient_memory", "wrong",
                body(grant, Map.of()), request("127.0.0.1")).getBody())
                .containsEntry("error", "INTERNAL_TOKEN_INVALID");
        assertThat(controller.invoke("read_patient_memory", null,
                body(grant, Map.of()), request("127.0.0.1")).getBody())
                .containsEntry("error", "INTERNAL_TOKEN_INVALID");

        config.setInternalToken("");
        assertThat(controller.invoke("read_patient_memory", "",
                body(grant, Map.of()), request("127.0.0.1")).getBody())
                .containsEntry("error", "INTERNAL_TOKEN_INVALID");
        verify(toolService, never()).execute(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("凭证缺失/伪造/过期 403，且返回同一句话不给探测留信息")
    void rejectsBadGrant() {
        assertThat(controller.invoke("read_patient_memory", TOKEN,
                body("mgrant_forged", Map.of()), request("127.0.0.1")).getBody())
                .containsEntry("error", "INTERNAL_GRANT_INVALID");
        assertThat(controller.invoke("read_patient_memory", TOKEN,
                body(null, Map.of()), request("127.0.0.1")).getBody())
                .containsEntry("error", "INTERNAL_GRANT_INVALID");
        verify(toolService, never()).execute(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("凭证范围外的工具 403 MCP_TOOL_REJECTED，即便子进程硬调也读不到")
    void rejectsToolOutsideGrantScopes() {
        String grant = grants.issue(OWNER, PATIENT, Set.of("memory"));

        ResponseEntity<Map<String, Object>> response = controller.invoke("get_patient_statistics",
                TOKEN, body(grant, Map.of()), request("127.0.0.1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "MCP_TOOL_REJECTED");
        verify(toolService, never()).execute(any(), anyString(), anyString(), any());
    }

    // ------------------------------------------------------------ 正常路径

    @Test
    @DisplayName("身份来自凭证而非请求体：请求体里的 owner/patientId 被忽略")
    void identityComesFromGrantOnly() {
        when(toolService.execute(any(), anyString(), anyString(), any()))
                .thenReturn(Map.of("status", "available", "patientId", PATIENT));
        String grant = grants.issue(OWNER, PATIENT, Set.of("memory"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("grant", grant);
        // 故意塞进来试图越权：端点的实现里根本没人读这两个键
        payload.put("owner", "somebody-else");
        payload.put("patientId", "pat_other");
        payload.put("arguments", Map.of("resourceId", "doc_1", "patientId", "pat_other"));

        ResponseEntity<Map<String, Object>> response = controller.invoke("read_patient_memory",
                TOKEN, payload, request("127.0.0.1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("structuredContent");
        verify(toolService).execute(eq(AgentTool.READ_PATIENT_MEMORY), eq(OWNER), eq(PATIENT),
                eq(Map.of("resourceId", "doc_1")));
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    @DisplayName("执行异常收敛为 500 MCP_UNAVAILABLE，响应体不带堆栈/连接串")
    void executionFailureDoesNotLeak() {
        when(toolService.execute(any(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("jdbc:postgresql://secret/db password=xxx"));
        String grant = grants.issue(OWNER, PATIENT, Set.of("memory"));

        ResponseEntity<Map<String, Object>> response = controller.invoke("read_patient_memory",
                TOKEN, body(grant, Map.of()), request("127.0.0.1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("error", "MCP_UNAVAILABLE");
        assertThat(String.valueOf(response.getBody()))
                .doesNotContain("jdbc:", "password", "IllegalStateException", "xxx");
    }

    @Test
    @DisplayName("空输出算 MCP_OUTPUT_INVALID：子进程不会拿到一个空对象当结果")
    void emptyOutputIsInvalid() {
        when(toolService.execute(any(), anyString(), anyString(), any())).thenReturn(Map.of());
        String grant = grants.issue(OWNER, PATIENT, Set.of("memory"));

        assertThat(controller.invoke("read_patient_memory", TOKEN, body(grant, Map.of()),
                request("127.0.0.1")).getBody())
                .containsEntry("error", "MCP_OUTPUT_INVALID");
    }

    // ------------------------------------------------------------ 夹具

    private static Map<String, Object> body(String grant, Map<String, Object> arguments) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (grant != null) {
            body.put("grant", grant);
        }
        body.put("arguments", arguments);
        return body;
    }

    private static HttpServletRequest request(String remoteAddr) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        return request;
    }
}
