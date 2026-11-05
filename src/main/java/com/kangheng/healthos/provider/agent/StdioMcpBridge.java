package com.kangheng.healthos.provider.agent;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;

import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.spi.agent.McpBridgeException;
import com.kangheng.healthos.spi.agent.McpServerBridge;
import com.kangheng.healthos.spi.agent.McpToolGrants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 真的 MCP 客户端：拉起子进程，走 stdio 上的 JSON-RPC 2.0。
 *
 * <p><b>分帧方式</b>：MCP 的 stdio transport 是「一行一条 JSON」，不是 LSP 的
 * {@code Content-Length} 头。搞错这一点会得到一个"能启动、永远读不到响应"的服务。
 *
 * <p><b>每次操作拉起一个进程</b>（握手完就退出）。理由：
 * <ul>
 *   <li>身份由父进程按登录态锁定后签发成一张短命凭证，通过环境变量只把凭证交给子进程；
 *       常驻进程要复用就得把身份塞进请求，等于把"服务端锁定"降级成"客户端声明"；</li>
 *   <li>常驻进程要额外处理空闲回收、崩溃重启、僵尸进程，收益只是省几十毫秒——</li>
 *   <li>这是诊断控制台，不是热路径。一次 probe 多花 60ms 无所谓。</li>
 * </ul>
 *
 * <p><b>失败一律收敛成 {@link McpBridgeException}</b>：不把子进程 stderr 原文往上抛，
 * 因为它可能夹带命令行与令牌；原始输出只进日志（截断到 500 字）。
 */
public class StdioMcpBridge implements McpServerBridge {

    private static final Logger log = LoggerFactory.getLogger(StdioMcpBridge.class);

    public static final String TRANSPORT = "stdio";

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final int STDERR_LIMIT = 500;

    private final HealthOsProperties.AgentLab config;
    private final McpToolGrants grants;
    private final AtomicLong sequence = new AtomicLong(1);

    public StdioMcpBridge(HealthOsProperties.AgentLab config, McpToolGrants grants) {
        this.config = config;
        this.grants = grants;
    }

    @Override
    public String transport() {
        return TRANSPORT;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Handshake handshake(Set<String> scopeWires, String owner, String patientId) {
        long started = System.nanoTime();
        try (Session session = openFor("initialize", scopeWires, owner, patientId)) {
            JsonNode init = session.request("initialize", Map.of(
                    "protocolVersion", PROTOCOL_VERSION,
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "health-os-java", "version", "0.1.0")));
            long initializeMs = (System.nanoTime() - started) / 1_000_000;
            // initialized 是通知：没有 id，也没有响应，必须发，否则严格实现的 server 会拒绝后续请求
            session.notify("notifications/initialized", Map.of());
            JsonNode list = session.request("tools/list", Map.of());
            List<String> tools = new ArrayList<>();
            for (JsonNode item : list.path("tools")) {
                String name = item.path("name").asText("");
                if (!name.isEmpty()) {
                    tools.add(name);
                }
            }
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            return new Handshake(init.path("protocolVersion").asText(PROTOCOL_VERSION),
                    init.path("serverInfo").path("name").asText(""),
                    init.path("serverInfo").path("version").asText(""),
                    List.copyOf(tools), elapsed, initializeMs);
        }
    }

    @Override
    public Map<String, Object> callTool(Set<String> scopeWires, String tool, String owner,
                                        String patientId, Map<String, Object> arguments) {
        try {
            return doCallTool(scopeWires, tool, owner, patientId, arguments);
        } catch (McpBridgeException ex) {
            // 结果校验（isError / 缺 structuredContent）发生在 request() 之外，
            // 那几处抛的异常没有阶段，在这里统一归到 tools/call。
            throw ex.atStage("tools/call");
        }
    }

    private Map<String, Object> doCallTool(Set<String> scopeWires, String tool, String owner,
                                           String patientId, Map<String, Object> arguments) {
        try (Session session = openFor("tools/call", scopeWires, owner, patientId)) {
            session.request("initialize", Map.of(
                    "protocolVersion", PROTOCOL_VERSION,
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "health-os-java", "version", "0.1.0")));
            session.notify("notifications/initialized", Map.of());
            JsonNode result = session.request("tools/call", Map.of(
                    "name", tool,
                    "arguments", arguments == null ? Map.of() : arguments));
            if (result.path("isError").asBoolean(false)) {
                String message = result.path("content").path(0).path("text").asText("工具执行失败");
                throw new McpBridgeException("MCP_TOOL_ERROR", brief(message));
            }
            JsonNode structured = result.path("structuredContent");
            if (structured.isMissingNode() || structured.isNull() || !structured.isObject()) {
                throw new McpBridgeException("MCP_OUTPUT_INVALID", "工具输出缺少 structuredContent");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> output = Json.mapper().convertValue(structured, LinkedHashMap.class);
            if (output == null || output.isEmpty()) {
                throw new McpBridgeException("MCP_OUTPUT_INVALID", "工具输出为空");
            }
            return output;
        }
    }

    // ------------------------------------------------------------ 进程与会话

    /** 起进程失败与"哪一步在起进程"绑定，否则回执里只能记成没有阶段。 */
    private Session openFor(String stage, Set<String> scopeWires, String owner, String patientId) {
        try {
            return open(scopeWires, owner, patientId);
        } catch (McpBridgeException ex) {
            throw ex.atStage(stage);
        }
    }

    private Session open(Set<String> scopeWires, String owner, String patientId) {
        if (config.getNodeCommand() == null || config.getNodeCommand().isBlank()) {
            throw new McpBridgeException("MCP_UNAVAILABLE", "node 命令未配置");
        }
        if (config.getServerScript() == null || config.getServerScript().isBlank()) {
            throw new McpBridgeException("MCP_UNAVAILABLE", "MCP server 脚本路径未配置");
        }
        ProcessBuilder builder = new ProcessBuilder(config.getNodeCommand(), config.getServerScript());
        builder.environment().put("MCP_TOOL_ENDPOINT", nullSafe(config.getToolEndpoint()));
        builder.environment().put("MCP_INTERNAL_TOKEN", nullSafe(config.getInternalToken()));
        // 只递凭证，不递身份：子进程没有能力指定 owner/patientId，回调时凭凭证换回服务端身份。
        // 递环境变量等于把身份交给子进程，那就成了"客户端声明自己是谁"。
        builder.environment().put("MCP_GRANT", grants.issue(owner, patientId, scopeWires));
        // 只递范围，不递工具清单：子进程自己维护"范围 → 工具"映射并裁剪声明，
        // 否则"发现集等于授权集"就成了拿同一份数据自己跟自己比。
        builder.environment().put("MCP_SCOPES", scopeWires == null ? "" : String.join(",", scopeWires));
        builder.environment().put("MCP_CALL_TIMEOUT_MS",
                String.valueOf(Math.max(1000, config.getTimeout().toMillis())));
        Process process;
        try {
            process = builder.start();
        } catch (IOException ex) {
            throw new McpBridgeException("MCP_UNAVAILABLE", "启动 MCP 子进程失败：" + ex.getMessage(), ex);
        }
        return new Session(process, config.getTimeout());
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }

    /** 一次操作一个会话：起进程 → 请求 → 关进程。 */
    private final class Session implements AutoCloseable {

        private final Process process;
        private final BufferedWriter stdin;
        private final BlockingQueue<String> stdout = new LinkedBlockingQueue<>();
        private final StringBuilder stderr = new StringBuilder();
        private final Duration timeout;
        private final Thread reader;
        private final Thread errReader;
        private volatile boolean eof;

        Session(Process process, Duration timeout) {
            this.process = process;
            this.timeout = timeout;
            this.stdin = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.reader = new Thread(this::pumpStdout, "mcp-stdio-reader");
            this.errReader = new Thread(this::pumpStderr, "mcp-stdio-stderr");
            reader.setDaemon(true);
            errReader.setDaemon(true);
            reader.start();
            errReader.start();
        }

        private void pumpStdout() {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (!line.isBlank()) {
                        stdout.add(line);
                    }
                }
            } catch (IOException ex) {
                log.debug("MCP stdout 读取结束：{}", ex.getMessage());
            } finally {
                eof = true;
                stdout.add("__EOF__");
            }
        }

        private void pumpStderr() {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (stderr.length() < STDERR_LIMIT) {
                        stderr.append(line).append('\n');
                    }
                }
            } catch (IOException ex) {
                log.debug("MCP stderr 读取结束：{}", ex.getMessage());
            }
        }

        JsonNode request(String method, Object params) {
            long id = sequence.getAndIncrement();
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("jsonrpc", "2.0");
            message.put("id", id);
            message.put("method", method);
            message.put("params", params);
            try {
                send(message);
                JsonNode response = await(id);
                JsonNode error = response.path("error");
                if (!error.isMissingNode() && !error.isNull()) {
                    throw new McpBridgeException("MCP_PROTOCOL_ERROR",
                            method + " 失败：" + brief(error.path("message").asText("")));
                }
                return response.path("result");
            } catch (McpBridgeException ex) {
                // 把"挂在哪一步"钉在这条异常上：调用方据此决定回执里哪条 event 记 error
                throw ex.atStage(method);
            }
        }

        void notify(String method, Object params) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("jsonrpc", "2.0");
            message.put("method", method);
            message.put("params", params);
            send(message);
        }

        private void send(Map<String, Object> message) {
            try {
                stdin.write(Json.write(message));
                stdin.write("\n");
                stdin.flush();
            } catch (IOException ex) {
                throw new McpBridgeException("MCP_UNAVAILABLE", "写入子进程失败：" + ex.getMessage(), ex);
            }
        }

        private JsonNode await(long wantedId) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new McpBridgeException("MCP_TIMEOUT",
                            "MCP 子进程 " + timeout.toSeconds() + "s 内没有响应（stderr：" + brief(stderr.toString()) + "）");
                }
                String line;
                try {
                    line = stdout.poll(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new McpBridgeException("MCP_UNAVAILABLE", "等待子进程响应被中断", ex);
                }
                if (line == null) {
                    throw new McpBridgeException("MCP_TIMEOUT", "等待 MCP 子进程响应超时");
                }
                if ("__EOF__".equals(line)) {
                    throw new McpBridgeException("MCP_PROCESS_EXITED",
                            "MCP 子进程提前退出（stderr：" + brief(stderr.toString()) + "）");
                }
                JsonNode node;
                try {
                    node = Json.mapper().readTree(line);
                } catch (IOException ex) {
                    // 一行坏 JSON 不该让整次握手失败：跳过并继续等目标响应
                    log.warn("MCP 子进程输出了无法解析的行，已跳过（长度 {}）", line.length());
                    continue;
                }
                if (node.hasNonNull("id") && node.path("id").asLong() == wantedId) {
                    return node;
                }
                // 服务端通知（没有 id）或其它响应：忽略
            }
        }

        @Override
        public void close() {
            try {
                stdin.close();
            } catch (IOException ignored) {
                // 进程已经退出时写 stdin 会抛，忽略即可
            }
            try {
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroy();
                    if (!process.waitFor(1, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            if (eof && stderr.length() > 0) {
                log.debug("MCP 子进程 stderr：{}", stderr.toString().trim());
            }
        }
    }
}
