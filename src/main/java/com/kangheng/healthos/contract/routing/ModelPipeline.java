package com.kangheng.healthos.contract.routing;

import java.util.List;
import java.util.Map;

/**
 * 七条**业务主线**的固定目录。
 *
 * <p>对应原工程 {@code model_route_service.CATALOG}。每条主线有自己的模型、参数与密钥，
 * 互不影响——"抽取典籍用的模型"和"给患者答疑问用的模型"本来就该分开，
 * 否则调问答的突发流量会把典籍抽取拖垮。
 *
 * <p>{@code sensitivity} 是数据敏感度，{@code capabilities} 是该主线要求模型具备的能力，
 * 二者共同决定配置校验时放行什么、拦住什么。
 */
public enum ModelPipeline {

    PATIENT_RECORD("客户资料标准化提取", "HIGH", List.of("connectivity", "structured")),
    MEDICAL_CLASSIC("医学典籍实体关系抽取", "NORMAL", List.of("connectivity", "structured")),
    REPORT("综合健康报告生成", "HIGH", List.of("connectivity", "structured", "streaming")),
    HEALTH_PLAN("生活方式计划生成", "HIGH", List.of("connectivity", "structured", "streaming")),
    PATIENT_QA("智能问答与汇总", "HIGH", List.of("connectivity", "structured", "streaming")),
    PATIENT_MEMORY("客户长效记忆更新", "HIGH", List.of("connectivity", "tool_calling")),
    MCP_AGENT("智能助手与 MCP 协作", "HIGH", List.of("connectivity", "tool_calling"));

    private final String label;
    private final String sensitivity;
    private final List<String> capabilities;

    ModelPipeline(String label, String sensitivity, List<String> capabilities) {
        this.label = label;
        this.sensitivity = sensitivity;
        this.capabilities = List.copyOf(capabilities);
    }

    public String label() {
        return label;
    }

    public String sensitivity() {
        return sensitivity;
    }

    public List<String> capabilities() {
        return capabilities;
    }

    /** 按 key 解析；未知 key 返回 {@code null}（调用方据此报 404）。 */
    public static ModelPipeline of(String key) {
        for (ModelPipeline pipeline : values()) {
            if (pipeline.name().equals(key)) {
                return pipeline;
            }
        }
        return null;
    }

    /**
     * 该主线是否要求兼容 Chat Completions 的协议。
     *
     * <p>这几条主线的调用点都走非流式结构化输出，Responses 协议拿不到一样的字段，
     * 硬接只会得到一份结构不对的结果——所以协议不匹配时直接拦住，而不是"尽力解析"。
     */
    public boolean requiresChatProtocol() {
        return this == PATIENT_RECORD || this == REPORT || this == HEALTH_PLAN
                || this == MCP_AGENT || this == PATIENT_MEMORY || this == PATIENT_QA;
    }

    /** 该主线是否走 Agent 侧的显式重试与预算约束。 */
    public boolean isAgentPipeline() {
        return this == MCP_AGENT || this == PATIENT_MEMORY;
    }
}
