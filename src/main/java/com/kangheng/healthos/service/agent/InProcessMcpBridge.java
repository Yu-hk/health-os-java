package com.kangheng.healthos.service.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kangheng.healthos.contract.agent.AgentCollaborationContract.AgentScope;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract.AgentTool;
import com.kangheng.healthos.contract.agent.AgentLabContract;
import com.kangheng.healthos.spi.agent.McpBridgeException;
import com.kangheng.healthos.spi.agent.McpServerBridge;

/**
 * 进程内「MCP 服务」：直接枚举目录、直接查库。
 *
 * <p>它是默认实现，也是没有 Node 时的退路。与 {@code StdioMcpBridge} 的区别只在传输层：
 * 两者都要求"服务端自己声明工具清单"，只是这里的"服务端"是同一个 JVM 里的一段代码。
 *
 * <p>不要因为它简单就绕过它：{@code AgentLabService} 只认 {@link McpServerBridge}，
 * 换成子进程实现时上层一行都不用改——这样"进程内"与"真子进程"才是可对照的两种形态。
 */
public class InProcessMcpBridge implements McpServerBridge {

    public static final String TRANSPORT = "in-process";

    private final AgentLabToolService toolService;

    public InProcessMcpBridge(AgentLabToolService toolService) {
        this.toolService = toolService;
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
        Set<String> discovered = toolService.discover(scopesOf(scopeWires));
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        // 进程内没有真正的握手往返，initialize 记 0，tools/list 记实际枚举耗时
        return new Handshake(AgentLabContract.PROTOCOL_VERSION, AgentLabContract.SERVICE_ID,
                AgentLabContract.CATALOG_VERSION, new ArrayList<>(discovered), elapsed, 0L);
    }

    @Override
    public Map<String, Object> callTool(Set<String> scopeWires, String tool, String owner,
                                        String patientId, Map<String, Object> arguments) {
        Map<String, Object> output = toolService.execute(AgentTool.of(tool), owner, patientId, arguments);
        if (output == null || output.isEmpty()) {
            throw new McpBridgeException("MCP_OUTPUT_INVALID", "工具输出为空");
        }
        return output;
    }

    /**
     * 范围 wire 名 → 枚举。
     *
     * <p>与子进程实现里的 {@code SCOPE_TOOLS} 是同一件事的两份写法：这里是 Java 枚举反射式映射，
     * 那边是 {@code patient-business.js} 里手写的一张表。两份独立维护，
     * 漂移会在握手期被 {@code AGENT_DISCOVERY_MISMATCH} 拦下。
     */
    private static Set<AgentScope> scopesOf(Set<String> scopeWires) {
        Set<AgentScope> scopes = new LinkedHashSet<>();
        if (scopeWires == null) {
            return scopes;
        }
        for (String wire : scopeWires) {
            AgentScope scope = AgentScope.of(wire);
            if (scope != null) {
                scopes.add(scope);
            }
        }
        return scopes;
    }

    /** 供目录页展示"这台服务是怎么接的"。 */
    public static List<String> declaredTools() {
        List<String> wires = new ArrayList<>();
        AgentLabContract.configuredTools().forEach(tool -> wires.add(tool.name()));
        return List.copyOf(wires);
    }
}
