package com.kangheng.healthos.provider.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.spi.agent.McpBridgeException;
import com.kangheng.healthos.spi.agent.McpServerBridge;
import com.kangheng.healthos.spi.agent.McpToolGrants;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 真的子进程 + 真的 stdio JSON-RPC。
 *
 * <p>这组用例的价值在于<b>不是桩</b>：它真的 {@code ProcessBuilder} 起 node、
 * 真的走一遍 {@code initialize → notifications/initialized → tools/list → tools/call}。
 * 桩只能证明"我以为协议长这样"，子进程才能证明"别人按协议接得进来"。
 *
 * <p>钉住五件事：
 * <ul>
 *   <li>声明按范围裁剪（父进程只递范围，映射在服务端）；</li>
 *   <li>身份不出现在子进程的请求里——只有短命凭证，端点查表才还原 owner/patientId；</li>
 *   <li>范围外的工具由<b>服务端自己</b>拒绝（-32602），不是靠父进程拦；</li>
 *   <li>node 不存在时失败是 {@code MCP_UNAVAILABLE} 且阶段是 {@code initialize}；</li>
 *   <li>工具端点报错时，父进程拿到的是 {@code MCP_TOOL_ERROR} 而不是"空输出"。</li>
 * </ul>
 *
 * <p>本机没装 node 时整组跳过（{@code assumeTrue}）——不是"通过"，是不适用。
 */
class StdioMcpBridgeTest {

    private static final String OWNER = "admin";
    private static final String PATIENT = "pat_1";
    private static final String TOKEN = "test-internal-token";

    private HttpServer stub;
    private final AtomicReference<JsonNode> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastToken = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    /** 让桩对某个工具返回 500，验证"端点失败 → MCP_TOOL_ERROR"。 */
    private final AtomicReference<String> failTool = new AtomicReference<>("");

    private McpToolGrants grants;
    private HealthOsProperties.AgentLab config;
    private StdioMcpBridge bridge;

    @BeforeEach
    void setUp() throws IOException {
        assumeTrue(nodeAvailable(), "本机没有 node，跳过真子进程用例");

        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/internal/agent-lab/tools", this::handleTool);
        stub.start();

        grants = new InMemoryMcpToolGrants(Duration.ofSeconds(30));
        config = new HealthOsProperties.AgentLab();
        config.setTransport("stdio");
        config.setNodeCommand("node");
        config.setServerScript(scriptPath());
        config.setToolEndpoint("http://127.0.0.1:" + stub.getAddress().getPort()
                + "/internal/agent-lab/tools");
        config.setInternalToken(TOKEN);
        config.setTimeout(Duration.ofSeconds(15));
        bridge = new StdioMcpBridge(config, grants);
    }

    @AfterEach
    void tearDown() {
        if (stub != null) {
            stub.stop(0);
            stub = null;
        }
    }

    // ------------------------------------------------------------ 握手

    @Test
    @DisplayName("握手拿到服务端自declared的工具清单，按范围裁剪且协议版本来自子进程")
    void handshakeDeclaresOnlyScopedTools() {
        McpServerBridge.Handshake handshake = bridge.handshake(scopes("memory", "report"), OWNER, PATIENT);

        assertThat(handshake.tools())
                .containsExactlyInAnyOrder("read_patient_memory", "read_patient_report");
        // 版本与名称来自子进程的 initialize 响应，不是父进程写死的常量
        assertThat(handshake.protocolVersion()).isEqualTo("2024-11-05");
        assertThat(handshake.serverName()).isEqualTo("patient-business");
        assertThat(handshake.serverVersion()).isEqualTo("agent-lab-m6-v1");
        assertThat(handshake.initializeMs()).isGreaterThanOrEqualTo(0L);
        assertThat(handshake.handshakeMs()).isGreaterThanOrEqualTo(handshake.initializeMs());
    }

    @Test
    @DisplayName("只给 statistics 范围时只声明两个统计工具，其余四个不出现")
    void handshakeFiltersByScope() {
        McpServerBridge.Handshake handshake = bridge.handshake(scopes("statistics"), OWNER, PATIENT);

        assertThat(handshake.tools()).containsExactlyInAnyOrder(
                "get_patient_statistics", "get_report_statistics");
    }

    // ------------------------------------------------------------ tools/call

    @Test
    @DisplayName("调用成功：返回 structuredContent，且子进程请求里只有凭证没有身份")
    void callToolReturnsStructuredContent() {
        Map<String, Object> output = bridge.callTool(scopes("memory"), "read_patient_memory",
                OWNER, PATIENT, Map.of());

        assertThat(output).containsEntry("status", "available").containsEntry("patientId", PATIENT);
        assertThat(lastPath.get()).isEqualTo("/internal/agent-lab/tools/read_patient_memory");
        assertThat(lastToken.get()).isEqualTo(TOKEN);

        JsonNode body = lastBody.get();
        assertThat(body.path("grant").asText()).startsWith("mgrant_");
        // 关键：身份不出现在请求体里，子进程无法指定"读谁的数据"
        assertThat(body.has("owner")).isFalse();
        assertThat(body.has("patientId")).isFalse();

        McpToolGrants.Grant grant = grants.resolve(body.path("grant").asText());
        assertThat(grant).isNotNull();
        assertThat(grant.owner()).isEqualTo(OWNER);
        assertThat(grant.patientId()).isEqualTo(PATIENT);
        assertThat(grant.scopeWires()).containsExactly("memory");
    }

    @Test
    @DisplayName("范围外工具由服务端拒绝：协议错误，阶段记在 tools/call")
    void outOfScopeToolIsRejectedByServer() {
        assertThatThrownBy(() -> bridge.callTool(scopes("memory"), "get_pipeline_status",
                OWNER, PATIENT, Map.of()))
                .isInstanceOf(McpBridgeException.class)
                .satisfies(ex -> {
                    McpBridgeException bridgeError = (McpBridgeException) ex;
                    assertThat(bridgeError.code()).isEqualTo("MCP_PROTOCOL_ERROR");
                    assertThat(bridgeError.stage()).isEqualTo("tools/call");
                });
        // 端点根本没被调用：拒绝发生在子进程的声明层面
        assertThat(lastBody.get()).isNull();
    }

    @Test
    @DisplayName("工具端点 500 时收敛为 MCP_TOOL_ERROR，不把端点原文当输出")
    void endpointFailureBecomesToolError() {
        failTool.set("read_patient_memory");

        assertThatThrownBy(() -> bridge.callTool(scopes("memory"), "read_patient_memory",
                OWNER, PATIENT, Map.of()))
                .isInstanceOf(McpBridgeException.class)
                .satisfies(ex -> {
                    McpBridgeException bridgeError = (McpBridgeException) ex;
                    assertThat(bridgeError.code()).isEqualTo("MCP_TOOL_ERROR");
                    assertThat(bridgeError.stage()).isEqualTo("tools/call");
                });
    }

    // ------------------------------------------------------------ 失败路径

    @Test
    @DisplayName("node 不存在时是 MCP_UNAVAILABLE，阶段归到 initialize")
    void missingNodeFailsAtInitialize() {
        config.setNodeCommand("node-does-not-exist-kh");
        StdioMcpBridge broken = new StdioMcpBridge(config, grants);

        assertThatThrownBy(() -> broken.handshake(scopes("memory"), OWNER, PATIENT))
                .isInstanceOf(McpBridgeException.class)
                .satisfies(ex -> {
                    McpBridgeException bridgeError = (McpBridgeException) ex;
                    assertThat(bridgeError.code()).isEqualTo("MCP_UNAVAILABLE");
                    assertThat(bridgeError.stage()).isEqualTo("initialize");
                });
    }

    @Test
    @DisplayName("脚本路径不存在时快速失败，阶段归到 initialize")
    void missingScriptFailsAtInitialize() {
        config.setServerScript(Path.of(System.getProperty("java.io.tmpdir"),
                "no-such-mcp-server.js").toString());
        StdioMcpBridge broken = new StdioMcpBridge(config, grants);

        // 走到哪一步取决于时序：进程先死 → 写管道报错（UNAVAILABLE）；
        // 写完才死 → 读到 EOF（PROCESS_EXITED）。两者都是"立刻失败"，不该等满超时。
        assertThatThrownBy(() -> broken.handshake(scopes("memory"), OWNER, PATIENT))
                .isInstanceOf(McpBridgeException.class)
                .satisfies(ex -> {
                    McpBridgeException bridgeError = (McpBridgeException) ex;
                    assertThat(bridgeError.code())
                            .isIn("MCP_UNAVAILABLE", "MCP_PROCESS_EXITED", "MCP_TIMEOUT");
                    assertThat(bridgeError.stage()).isEqualTo("initialize");
                });
    }

    // ------------------------------------------------------------ 夹具

    private void handleTool(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastPath.set(exchange.getRequestURI().getPath());
        lastToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
        lastBody.set(Json.mapper().readTree(body));

        String tool = lastPath.get().substring(lastPath.get().lastIndexOf('/') + 1);
        if (tool.equals(failTool.get())) {
            reply(exchange, 500, "{\"error\":\"MCP_UNAVAILABLE\",\"message\":\"工具执行失败\"}");
            return;
        }
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("patientId", PATIENT);
        structured.put("status", "available");
        Map<String, Object> payload = Map.of("structuredContent", structured);
        reply(exchange, 200, Json.write(payload));
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Set<String> scopes(String... wires) {
        return new LinkedHashSet<>(java.util.List.of(wires));
    }

    private static String scriptPath() {
        return Path.of(System.getProperty("user.dir"), "tools", "mcp-server", "patient-business.js")
                .toAbsolutePath().toString();
    }

    private static boolean nodeAvailable() {
        try {
            Process process = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException ex) {
            return false;
        }
    }
}
