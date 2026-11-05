package com.kangheng.healthos.provider.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kangheng.healthos.spi.agent.AgentRoleExecutor;

import org.springframework.stereotype.Component;

/**
 * 模板化的角色执行器。
 *
 * <p>它<b>不调用任何模型</b>，只把编排层已经读好的事实组织成结构化产出。
 *
 * <p>这不是"降级的假实现"，而是这套协同里最该先有的那一档：
 * 多智能体编排的难点从来不在"让模型说话"，而在<b>让四个角色各自守规矩、
 * 交接可追溯、来源能核对</b>。用确定性执行器跑一遍，等于先证明编排骨架是对的——
 * 等接上模型时，变的只是措辞，不是"能不能引用没读过的来源"这条底线。
 *
 * <p>它的三条自律：
 * <ul>
 *   <li>只引用自己读到的（工具读取 + 上游交接）来源，一个都不多；</li>
 *   <li>读不到就写进 limitations，不装作读到；</li>
 *   <li>不写思维链——产出只有结论、进展、局限、来源四段。</li>
 * </ul>
 */
@Component
public class TemplateAgentRoleExecutor implements AgentRoleExecutor {

    @Override
    public RoleOutcome execute(RoleRequest request) {
        List<String> progress = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        Set<String> sourceKeys = new LinkedHashSet<>();
        List<Source> sources = new ArrayList<>();

        for (ToolReading reading : request.readings()) {
            progress.add(describe(reading));
            String note = string(reading.output().get("note"));
            if (!note.isBlank()) {
                limitations.add(note);
            }
            for (Source source : reading.sources()) {
                if (sourceKeys.add(source.id() + "#" + (source.version() == null ? "-" : source.version()))) {
                    sources.add(source);
                }
            }
        }
        for (Source source : request.upstreamSources()) {
            if (sourceKeys.add(source.id() + "#" + (source.version() == null ? "-" : source.version()))) {
                sources.add(source);
            }
        }
        if (!request.upstreamSources().isEmpty()) {
            progress.add("已接收上游角色交接的来源 " + request.upstreamSources().size() + " 项");
        }
        limitations.addAll(defaultLimitations(request.role()));
        if (progress.isEmpty()) {
            progress.add("本角色本次没有可读取的记录");
        }

        Answer answer = new Answer(summary(request, request.readings()), progress, limitations, sources);
        return new RoleOutcome(answer, request.readings());
    }

    /** 结论段：只陈述做了什么、依据什么，不做推断。 */
    private String summary(RoleRequest request, List<ToolReading> readings) {
        String role = request.role();
        Map<String, Object> first = readings.isEmpty() ? Map.of() : readings.get(0).output();
        return switch (role) {
            case "patient_memory_manager" -> "已读取该患者已发布的长效记忆版本 "
                    + first.getOrDefault("version", "未知") + "，来源 "
                    + first.getOrDefault("source_count", "未知") + " 条。口述反馈不等同于已核验事实。";
            case "pipeline_inspector" -> noTask(first)
                    ? "已核对该患者的业务任务状态：当前没有任务记录。没有任务不等于所有业务正常。"
                    : "已核对该患者的业务任务状态与打卡进度；任务状态与打卡进度是两个不同口径，不可混用。";
            case "lifestyle_advisor" -> "已读取该患者已签发报告与生效计划，仅解释其中既有建议，未新增任何干预。";
            case "progress_synthesizer" -> "已汇总上游角色交接的事实、进展与局限，保留各自来源与版本。";
            default -> "已按角色职责完成本次读取。";
        };
    }

    /** 任务状态查不到任何记录。 */
    private boolean noTask(Map<String, Object> output) {
        Object counts = output.get("status_counts");
        return counts instanceof Map<?, ?> map && map.isEmpty();
    }

    /** 每个读取写一条进展，标明依据与采样限制。 */
    private String describe(ToolReading reading) {
        Map<String, Object> output = reading.output();
        StringBuilder text = new StringBuilder(reading.tool());
        Object memoryVersion = output.get("version");
        if (memoryVersion != null) {
            text.append("：记忆版本 v").append(memoryVersion);
        }
        Object reportId = output.get("report_id");
        if (reportId != null) {
            text.append("：报告 ").append(reportId).append("（修订 ")
                    .append(output.getOrDefault("revision", "未知")).append("）");
        }
        Object plan = output.get("plan");
        if (plan instanceof Map<?, ?> planMap) {
            text.append("：计划 ").append(planMap.get("document_id"))
                    .append("，打卡 ").append(planMap.get("completed_tasks"))
                    .append("/").append(planMap.get("total_tasks"))
                    .append("（采样时刻 ").append(planMap.get("sampled_at")).append("）");
        }
        Object counts = output.get("status_counts");
        if (counts instanceof Map<?, ?> countMap) {
            text.append(countMap.isEmpty() ? "：任务状态 无任务记录（不等于业务正常）" : "：任务状态 " + countMap);
        }
        Object patientCount = output.get("patient_count");
        if (patientCount != null) {
            text.append("：账号已建档患者 ").append(patientCount).append(" 人");
        }
        Object reportCount = output.get("report_count");
        if (reportCount != null) {
            text.append("：账号正式报告 ").append(reportCount).append(" 份");
        }
        text.append("；来源 ").append(reading.sources().size()).append(" 项");
        return text.toString();
    }

    /** 每个角色固定要说的局限。 */
    private List<String> defaultLimitations(String role) {
        List<String> limitations = new ArrayList<>();
        switch (role) {
            case "patient_memory_manager" -> limitations.add("记忆内容为采样时刻快照，不代表此刻状态");
            case "pipeline_inspector" -> limitations.add("打卡进度是采样时刻患者真实进度，不等于后台生成状态");
            case "lifestyle_advisor" -> limitations.add("仅解释已签发内容，未新增诊断、处方或干预建议");
            case "progress_synthesizer" -> limitations.add("汇总为健康管理辅助说明，不构成独立诊断或治疗建议");
            default -> {
            }
        }
        return limitations;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 便于测试与日志排查的稳定视图。 */
    public Map<String, Object> describe(RoleRequest request) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("role", request.role());
        view.put("tools", request.tools());
        view.put("readings", request.readings().size());
        view.put("upstream", request.upstreamSources().size());
        return view;
    }
}
