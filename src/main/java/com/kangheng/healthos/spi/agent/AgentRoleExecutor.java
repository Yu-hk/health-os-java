package com.kangheng.healthos.spi.agent;

import java.util.List;
import java.util.Map;

/**
 * 角色执行的扩展点。
 *
 * <p>编排层负责"谁该跑、跑完能不能用"（依赖顺序、跳过、来源校验、交接完整性），
 * 执行器只负责"这个角色基于已读取的事实怎么组织答案"。
 *
 * <p><b>工具数据由编排层读好再交给执行器</b>，而不是让执行器自己去调工具。
 * 理由：执行器可能是模型、也可能是模板实现，它说的话必须能被核对。
 * 如果工具调用由执行器自己发起，编排层就只能相信它"说自己读了"——
 * 那样"来源校验"就成了自证。<b>事实由编排层读，执行器只做组织</b>，
 * 来源能不能过校验才有意义。
 *
 * <p>类型刻意定义在接口里而不是复用对外契约：扩展点契约不该知道 API 长什么样。
 */
public interface AgentRoleExecutor {

    /**
     * 执行一个角色。
     *
     * <p>实现不得抛异常——角色失败是一条正常的业务结果，由编排层记录并处理。
     * 需要表达"我读到了什么"就返回 {@link RoleOutcome} 并带上 {@link ToolReading}。
     */
    RoleOutcome execute(RoleRequest request);

    /**
     * 角色执行请求。
     *
     * @param role        角色标识（如 {@code patient_memory_manager}）
     * @param prompt      该角色的完整提示词
     * @param instruction 本次用户指令
     * @param tools       本角色允许使用的工具
     * @param readings    编排层已经读好的工具结果
     * @param upstreamSources 上游角色交接过来的来源；角色可以引用它们，但不得引用别的
     */
    record RoleRequest(String runId,
                       String owner,
                       String patientId,
                       String role,
                       String prompt,
                       String instruction,
                       List<String> tools,
                       List<ToolReading> readings,
                       List<Source> upstreamSources) {

        public RoleRequest {
            tools = tools == null ? List.of() : List.copyOf(tools);
            readings = readings == null ? List.of() : List.copyOf(readings);
            upstreamSources = upstreamSources == null ? List.of() : List.copyOf(upstreamSources);
        }
    }

    /**
     * 一次工具读取的结果。
     *
     * @param tool    工具标识
     * @param output  工具返回的数据（已脱敏）
     * @param sources 该次读取产出的来源引用
     */
    record ToolReading(String tool, Map<String, Object> output, List<Source> sources) {

        public ToolReading {
            output = output == null ? Map.of() : Map.copyOf(output);
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }

    /** 来源引用。版本可空——任务类资源没有版本号。 */
    record Source(String id, Integer version) {
    }

    /** 角色产出。 */
    record Answer(String summary, List<String> progress, List<String> limitations, List<Source> sources) {

        public Answer {
            progress = progress == null ? List.of() : List.copyOf(progress);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }

    /** 角色执行结果。 */
    record RoleOutcome(Answer answer, List<ToolReading> readings) {

        public RoleOutcome {
            readings = readings == null ? List.of() : List.copyOf(readings);
        }
    }
}
