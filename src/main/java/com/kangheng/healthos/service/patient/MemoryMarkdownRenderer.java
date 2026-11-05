package com.kangheng.healthos.service.patient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 把冻结的事实快照渲染成一份可导出、可人工阅读的 Markdown。
 *
 * <p>对应原工程 {@code memory_service.render_markdown}。三条纪律：
 * <ul>
 *   <li><b>不生成新事实</b>：只把 {@code data} 里已有的字段写出来，缺了就写"暂无"，
 *       绝不补一句"综合来看"；</li>
 *   <li><b>标注来源性质</b>：指标是"当前状态采样"、随访是"患者自述原文"，
 *       这两类不能被读成已核验的医学结论；</li>
 *   <li><b>来源清单可核对</b>：末尾逐条列 {@code contentHash}，
 *       头部 {@code X-Content-SHA256} 校验的是整个文件（含这份清单）。</li>
 * </ul>
 *
 * <p>渲染结果参与 {@code contentHash} 计算，所以这个函数必须<b>确定性</b>：
 * 用 LinkedHashMap / 固定顺序遍历，不引入当前时间、不遍历 HashMap。
 */
@Component
public class MemoryMarkdownRenderer {

    public String render(String patientId, int version, Map<String, Object> data,
                         List<Map<String, Object>> sources, String sampledAt) {
        List<String> lines = new ArrayList<>();
        lines.add("# 患者长效记忆 v" + version);
        lines.add("");
        lines.add("- 患者：" + patientId);
        lines.add("- 采样时间：" + sampledAt);
        lines.add("- 结构版本：" + str(data.get("schemaVersion")));
        lines.add("- 整理方式：程序化事实快照（非模型生成）");

        // 1 ---------------------------------------------------------- 基线
        lines.add("");
        lines.add("## 1. 基础基线事实");
        Map<String, Object> baseline = map(data.get("baseline"));
        if (!Boolean.TRUE.equals(baseline.get("available"))) {
            lines.add("暂无已发布的档案修订快照。");
        } else {
            lines.add("来源：档案修订 r" + str(baseline.get("revision"))
                    + "（SHA256 " + str(baseline.get("snapshotSha256")) + "）");
            label(lines, "姓名", baseline.get("name"));
            label(lines, "性别", baseline.get("gender"));
            label(lines, "出生日期", baseline.get("birthDate"));
            label(lines, "外部编号", baseline.get("externalRef"));
            label(lines, "联系电话", baseline.get("phoneMasked"));
            label(lines, "档案状态", baseline.get("status"));
            label(lines, "风险等级", baseline.get("riskLevel"));
        }

        // 2 ---------------------------------------------------------- 指标
        lines.add("");
        lines.add("## 2. 当前指标采样（非已发布修订）");
        List<Map<String, Object>> metrics = maps(data.get("metrics"));
        if (metrics.isEmpty()) {
            lines.add("暂无指标记录。");
        } else {
            lines.add("以下为本次采样时刻的当前值，非已核验的历次体检修订，不可作为历史趋势引用。");
            for (Map<String, Object> metric : metrics) {
                lines.add("- " + str(metric.get("name")) + "（" + str(metric.get("code")) + "）："
                        + str(metric.get("value")) + " " + nullToEmpty(metric.get("unit"))
                        + "；参考 "
                        + str(metric.get("referenceLow")) + "~" + str(metric.get("referenceHigh"))
                        + "；判定 " + str(metric.get("status")));
            }
        }

        // 3 ---------------------------------------------------------- 报告
        lines.add("");
        lines.add("## 3. 已签发报告决议");
        Map<String, Object> report = map(data.get("report"));
        if (report.isEmpty()) {
            lines.add("暂无当前签发报告。");
        } else {
            lines.add("报告：" + str(report.get("documentId")) + " r" + str(report.get("revision")));
            lines.add("签署：" + str(report.get("signer")) + " · " + str(report.get("signedAt"))
                    + "（" + str(report.get("signatureKind")) + "）");
            for (Map<String, Object> section : maps(report.get("sections"))) {
                lines.add("");
                lines.add("### " + str(section.get("title")));
                lines.add(str(section.get("text")));
                List<?> refs = list(section.get("metricRefs"));
                lines.add("指标引用：" + (refs.isEmpty() ? "无" : String.join("、", refs.stream().map(this::str).toList())));
            }
        }

        // 4 ---------------------------------------------------------- 计划与打卡
        lines.add("");
        lines.add("## 4. 四周计划执行与打卡轨迹");
        Map<String, Object> plan = map(data.get("plan"));
        if (plan.isEmpty()) {
            lines.add("暂无已确认生效计划。");
        } else {
            lines.add("计划：" + str(plan.get("documentId")) + " r" + str(plan.get("revision"))
                    + "；开始：" + str(plan.get("startDate")));
            lines.add("完成：" + str(plan.get("completedTasks")) + "/" + str(plan.get("totalTasks"))
                    + "（" + str(plan.get("completionRate")) + "%）；打卡为当前状态采样");
            if (!Boolean.TRUE.equals(plan.get("matchesCurrentReport"))) {
                lines.add("注意：此计划不对应当前签发报告。");
            }
            for (Map<String, Object> task : maps(plan.get("tasks"))) {
                lines.add("- [" + (Boolean.TRUE.equals(task.get("completed")) ? "x" : " ") + "] "
                        + str(task.get("title")) + "（" + str(task.get("taskId")) + "）");
            }
        }

        // 5 ---------------------------------------------------------- 随访
        lines.add("");
        lines.add("## 5. 随访反馈原文（患者自述，非医学核验）");
        List<Map<String, Object>> followups = maps(data.get("followups"));
        if (followups.isEmpty()) {
            lines.add("暂无随访反馈；本版本未包含新增口述事实。");
        } else {
            for (Map<String, Object> feedback : followups) {
                lines.add("");
                lines.add("### " + str(feedback.get("occurredAt")) + " · " + str(feedback.get("feedbackId")));
                lines.add("录入：" + str(feedback.get("actor"))
                        + " · 保存时间：" + str(feedback.get("createdAt")));
                for (Map<String, Object> entry : maps(feedback.get("entries"))) {
                    lines.add("#### " + str(entry.get("categoryLabel")));
                    for (String line : str(entry.get("content")).split("\\R", -1)) {
                        lines.add("> " + line);
                    }
                }
            }
        }

        // 来源清单 -----------------------------------------------------
        lines.add("");
        lines.add("## 来源清单");
        if (sources.isEmpty()) {
            lines.add("本版本没有可登记的来源（患者尚无任何已发布事实）。");
        } else {
            for (Map<String, Object> source : sources) {
                String revision = source.get("revision") == null ? "-" : "r" + source.get("revision");
                lines.add("- " + str(source.get("kind")) + " · " + str(source.get("id"))
                        + " " + revision + " · SHA256 " + str(source.get("contentHash"))
                        + " · " + str(source.get("note")));
            }
        }
        return String.join("\n", lines) + "\n";
    }

    private void label(List<String> lines, String label, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            lines.add("- " + label + "：" + value);
        }
    }

    private String nullToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Map<?, ?>) {
                result.add((Map<String, Object>) item);
            }
        }
        return result;
    }

    private List<?> list(Object value) {
        return value instanceof List<?> raw ? raw : List.of();
    }
}
