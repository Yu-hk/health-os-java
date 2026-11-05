package com.kangheng.healthos.contract.agent;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract.AgentRole;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract.AgentScope;
import com.kangheng.healthos.contract.agent.AgentCollaborationContract.AgentTool;

/**
 * Agent Lab 工具目录与协议校验的**冻结契约**（M6）。
 *
 * <p>对应原 Python 工程 {@code agent_lab_catalog.py} 与 {@code agent_lab_api.py}。
 * 原工程会拉起一个 STDIO 子进程做 MCP 握手；Java 侧刻意做成**进程内协议校验**：
 * 三步 {@code initialize → tools/list → tools/call} 都直接对进程内工具目录做真实校验，
 * 不启子进程、不接外部 MCP Server。这样"发现集是否等于授权集"是可以被单测锁死的，
 * 也不会因为某个子进程挂了就让整个目录接口不可用。
 *
 * <p><b>复用既有枚举，不另造一套</b>：范围、工具、角色、场景全部取自
 * {@code AgentCollaborationContract}。Agent Lab 只是把这套已在协同里落地的工具清单
 * 换一个"可探测、可回执"的视角暴露出来；一旦另造枚举，两边清单迟早漂移，
 * 越权工具就会借着目录接口重新长出来。
 *
 * <p><b>为什么要有回执</b>：协议校验不是只读的——它要留下"这次握手到底发生了什么"。
 * 回执先以 running 落库、执行完再改状态，进程崩在中途也留得下痕迹。
 */
public final class AgentLabContract {

    private AgentLabContract() {
    }

    /** 目录版本。结构变了必须换号，否则前端拿旧缓存解释新字段会出错。 */
    public static final String CATALOG_VERSION = "agent-lab-m6-v1";
    /** 本进程内唯一注册的 MCP 服务 ID。 */
    public static final String SERVICE_ID = "patient-business";
    /** 进程内协议版本。仅作握手元信息，不参与业务判定。 */
    public static final String PROTOCOL_VERSION = "2024-11-05";
    /** 统计与采样时刻统一用东八区，避免"今天"跨时区漂移。 */
    public static final String TIMEZONE = "Asia/Shanghai";

    /** probe 成功后超过这个秒数，服务状态转为 stale（目录只反映最近一次观测）。 */
    public static final long STALE_SECONDS = 300L;
    /** running 回执超过这个秒数，在视图里显示为 interrupted（库里仍是 running）。 */
    public static final long INTERRUPTED_SECONDS = 35L;
    /** 一次请求最多授权 5 个范围（正好是全部范围）。 */
    public static final int MAX_SCOPES = 5;

    public static final String MCP_SERVICE_NOT_FOUND = "MCP_SERVICE_NOT_FOUND";
    public static final String MCP_TOOL_REJECTED = "MCP_TOOL_REJECTED";
    public static final String MCP_OUTPUT_INVALID = "MCP_OUTPUT_INVALID";
    public static final String MCP_UNAVAILABLE = "MCP_UNAVAILABLE";
    public static final String AGENT_DISCOVERY_MISMATCH = "AGENT_DISCOVERY_MISMATCH";

    /** 状态字面量（与库里存的一致，避免各处拼写漂移）。 */
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";
    public static final String KIND_PROBE = "probe";
    public static final String KIND_CALL = "call";

    // ------------------------------------------------------------ 授权表（独立于工具目录）

    /**
     * 授权表：每个范围允许出现的工具 wire 名。
     *
     * <p><b>刻意与工具目录分开维护</b>：目录来自 {@code AgentTool} 枚举的扫描，
     * 授权表是这里写死的一张白名单。{@code tools/list} 的发现集必须等于它——
     * 两处一旦漂移（新增工具忘了登记授权、或授权里写了目录里没有的工具），
     * 就会在握手期被 {@code AGENT_DISCOVERY_MISMATCH} 挡住。这是防 regess 的真实校验，
     * 不是"两个变量一比必然相等"的摆设。
     */
    private static final Map<AgentScope, List<String>> AUTHORIZED_TOOLS = Map.of(
            AgentScope.MEMORY, List.of("read_patient_memory"),
            AgentScope.REPORT, List.of("read_patient_report"),
            AgentScope.PIPELINE, List.of("get_pipeline_status"),
            AgentScope.PLAN, List.of("get_plan_progress"),
            AgentScope.STATISTICS, List.of("get_patient_statistics", "get_report_statistics"));

    /** 按授权范围求出本次允许的工具集合（白名单）。 */
    public static Set<String> authorizedToolWires(Set<AgentScope> scopes) {
        Set<String> result = new LinkedHashSet<>();
        for (AgentScope scope : scopes) {
            List<String> wires = AUTHORIZED_TOOLS.get(scope);
            if (wires != null) {
                result.addAll(wires);
            }
        }
        return result;
    }

    // ------------------------------------------------------------ 目录静态件

    /** 6 个只读工具的统一入参 schema：只允许一个可选的 resource_id。 */
    public static Map<String, Object> inputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        // 患者绝不作为参数传入：由服务端按 owner 锁定，见 AgentLabToolService。
        properties.put("resource_id", Map.of("type", "string",
                "description", "可选资源标识；患者由服务端按登录态锁定"));
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    /** 目录里的 6 个配置工具，顺序与 AgentTool 枚举一致。 */
    public static List<ConfiguredTool> configuredTools() {
        List<ConfiguredTool> tools = new ArrayList<>();
        for (AgentTool tool : AgentTool.values()) {
            tools.add(new ConfiguredTool(tool.wire(), tool.label(),
                    tool.scope().wire(), inputSchema()));
        }
        return List.copyOf(tools);
    }

    /** 全部可授权范围（wire 名）。 */
    public static List<String> scopeWires() {
        return Arrays.stream(AgentScope.values()).map(AgentScope::wire).toList();
    }

    /**
     * 4 个已配置 Agent，直接由 {@code AgentRole} 枚举派生，保证 id 与协同侧一致。
     *
     * <p>{@code executionStatus} 恒为 {@code idle}：本工程是进程内确定式执行，
     * 没有常驻的 Agent 运行时，"正在运行"这种状态没有事实依据，不能编。
     */
    public static List<AgentDescriptor> agentDescriptors() {
        List<AgentDescriptor> agents = new ArrayList<>();
        for (AgentRole role : AgentRole.values()) {
            agents.add(new AgentDescriptor(role.id(), role.displayName(),
                    description(role), tools(role),
                    AgentCollaborationContract.PROMPT_VERSION, "idle"));
        }
        return List.copyOf(agents);
    }

    private static String description(AgentRole role) {
        return switch (role) {
            case MEMORY -> "读取已发布长效记忆与来源，管理患者事实基线";
            case PIPELINE -> "核对患者业务任务状态与四周计划打卡进度";
            case LIFESTYLE -> "解释已签发报告与生效计划中的既有建议";
            case SYNTHESIS -> "汇总各角色交接与来源，产出综合进展说明";
        };
    }

    /** 角色可使用的工具清单（目录视角的并集），与协同侧 boundTools 对齐。 */
    private static List<String> tools(AgentRole role) {
        return switch (role) {
            case MEMORY -> List.of("read_patient_memory",
                    "get_patient_statistics", "get_report_statistics");
            case PIPELINE -> List.of("get_pipeline_status", "get_plan_progress");
            case LIFESTYLE -> List.of("read_patient_report", "get_plan_progress");
            // 汇总角色只消费上游交接，不直接读工具（与 boundTools 的 SYNTHESIS 分支一致）。
            case SYNTHESIS -> List.of();
        };
    }

    // ------------------------------------------------------------ 请求校验

    /**
     * 解析并校验请求里的 scopes：非空、最多 {@link #MAX_SCOPES} 个、不可重复、必须已知。
     *
     * <p>重复用 {@code LinkedHashSet.add} 的返回值判定，而不是比对原始字符串列表——
     * 因为 {@code AgentScope.of} 同时接受 wire 与枚举名，"memory" 和 "MEMORY"
     * 是同一个范围，字符串不重复但语义重复，照样要挡住。
     */
    public static Set<AgentScope> scopesOf(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw BizException.badRequest("scopes 不能为空");
        }
        if (raw.size() > MAX_SCOPES) {
            throw BizException.badRequest("scopes 最多 " + MAX_SCOPES + " 个");
        }
        Set<AgentScope> scopes = new LinkedHashSet<>();
        for (String value : raw) {
            AgentScope scope = AgentScope.of(value);
            if (scope == null) {
                throw BizException.badRequest("未知的授权范围：" + value);
            }
            if (!scopes.add(scope)) {
                throw BizException.badRequest("scopes 不可重复：" + value);
            }
        }
        return scopes;
    }

    /** 东八区当天 00:00（带 +08:00 偏移），用于"当日次数"边界。 */
    public static OffsetDateTime startOfToday() {
        ZoneId zone = ZoneId.of(TIMEZONE);
        return LocalDate.now(zone).atStartOfDay(zone).toOffsetDateTime();
    }

    // ------------------------------------------------------------ 请求 DTO

    /** probe 请求体。 */
    public record ProbeRequest(@NotBlank String patientId,
                               @NotEmpty @Size(max = MAX_SCOPES) List<String> scopes) {
    }

    /** call 请求体。arguments 只透传白名单字段，患者由服务端锁定。 */
    public record CallRequest(@NotBlank String patientId,
                              @NotEmpty @Size(max = MAX_SCOPES) List<String> scopes,
                              @NotBlank String tool,
                              Map<String, Object> arguments) {
    }

    // ------------------------------------------------------------ 响应 DTO

    /** 目录里的一个配置工具。 */
    public record ConfiguredTool(String name, String title, String scope,
                                 Map<String, Object> inputSchema) {
    }

    /** 目录里的一个已配置 Agent。 */
    public record AgentDescriptor(String id, String name, String description,
                                  List<String> tools, String promptVersion,
                                  String executionStatus) {
    }

    /** 目录里的一个服务。 */
    public record ServiceDescriptor(String id, String name, String transport,
                                    String status, boolean enabled, int configuredTools) {
    }

    /**
     * 目录指标。
     *
     * <p>当日次数一律来自 {@code agent_mcp_receipt} 真实统计，不硬编码：
     * {@code handshakeSamplesToday} 只数 probe（一次握手一份样本），
     * {@code agentCallsToday} 只数 call（一次工具调用一条），
     * 这就是"probe/call 分开计"；{@code manualCallsToday} 是当天手动触发的
     * 全部 lab 操作（probe + call）。{@code averageHandshakeMs} 取当天 probe 回执
     * {@code detail.handshakeMs} 的均值，没有样本时为 0。
     */
    public record Metrics(int configuredAgents, int registeredServices, int registeredTools,
                          long agentCallsToday, long manualCallsToday, long handshakeSamplesToday,
                          double averageHandshakeMs, String timezone) {
    }

    /** 目录总视图。 */
    public record CatalogView(String catalogVersion, String serviceId, List<String> scopes,
                              List<AgentDescriptor> agents, List<ConfiguredTool> configuredTools,
                              Metrics metrics, List<ServiceDescriptor> services) {
    }

    /**
     * 回执视图。
     *
     * <p>用 {@code @JsonInclude(ALWAYS)} 覆盖全局的 non_null：冻结契约要求
     * {@code completedAt} 等 8 个字段恒在，running 回执的 {@code completedAt}
     * 应显式为 {@code null} 而不是被省略。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ReceiptView(String id, String patientId, String kind, String status,
                              Map<String, Object> request, Map<String, Object> detail,
                              String createdAt, String completedAt) {
    }

    /** tools/call 的结构化产出。 */
    public record ToolCallOutput(Map<String, Object> structuredContent) {
    }

    /**
     * probe / call 的统一响应 {@code {receipt, output}}。
     *
     * <p>{@code @JsonInclude(ALWAYS)} 同样是必须的：全局 non_null 会把
     * probe 的 {@code output: null} 直接省略，而契约明确要求它是 {@code null}。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OperationView(ReceiptView receipt,
                                @JsonInclude(JsonInclude.Include.ALWAYS) Object output) {
    }
}
