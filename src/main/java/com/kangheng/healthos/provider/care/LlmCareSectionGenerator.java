package com.kangheng.healthos.provider.care;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.kangheng.healthos.contract.care.CareContract;
import com.kangheng.healthos.spi.ai.LlmException;
import com.kangheng.healthos.spi.ai.LlmProvider;
import com.kangheng.healthos.spi.care.CareSectionGenerator;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 模型生成器：把已确认的指标交给模型写成专业表述。
 *
 * <p>与规则实现共用同一份 {@link CareSectionGenerator} 接口，切换靠配置：
 * <pre>
 * healthos.care.generator: rule   # 默认，离线可跑
 * healthos.care.generator: llm    # 需要 healthos.ai.api-key
 * </pre>
 *
 * <p><b>安全约束</b>：传给模型的只有 {@code confirmedMetrics}（已人工核查过的指标）
 * 与基础信息，没有患者原始病历、没有未核查内容。模型返回的正文会被原样保存，
 * 但 {@code metricRefs} <b>不从模型返回里取</b>——而是由规则根据实际传入的指标
 * 计算。否则模型可能声称依据了某个并不存在的指标。
 */
@Component
@ConditionalOnProperty(name = "healthos.care.generator", havingValue = "llm")
public class LlmCareSectionGenerator implements CareSectionGenerator {

    private final LlmProvider llmProvider;

    public LlmCareSectionGenerator(LlmProvider llmProvider) {
        this.llmProvider = llmProvider;
    }

    @Override
    public String name() {
        return "llm:" + llmProvider.name();
    }

    @Override
    public SectionResult generate(String kind, String key, GenerationContext context) {
        return generateInternal(null, kind, key, context);
    }

    /** 走 REPORT / HEALTH_PLAN 主线：同一份文档的 6 节共用冻结的那一版配置。 */
    @Override
    public SectionResult generate(LlmProvider.RouteContext route, String kind, String key,
                                  GenerationContext context) {
        return generateInternal(route, kind, key, context);
    }

    private SectionResult generateInternal(LlmProvider.RouteContext route, String kind, String key,
                                           GenerationContext context) {
        String title = CareContract.SECTION_TITLES.get(key);
        if (title == null) {
            return SectionResult.failed("未知的分节: " + key);
        }
        List<CareSectionGenerator.MetricFact> metrics = context.confirmedMetrics();
        if (metrics.isEmpty()) {
            return SectionResult.failed("没有已确认指标，无法生成该分节");
        }

        String system = """
                你是健康管理人员，为受检者撰写健康评估与干预建议。
                硬性要求：
                1. 只使用【已确认指标】中给出的数据，不得引用未提供的数据，不得臆造数值；
                2. 不得给出诊断结论、不得推荐处方药；涉及用药一律写"请遵医嘱"；
                3. 涉及中医证型时，若无四诊信息必须写明资料不足、需面诊辨证；
                4. 输出纯正文，不要标题、不要 Markdown 标记、不要解释你在做什么；
                5. 长度 150–800 字。
                """;

        StringBuilder user = new StringBuilder();
        user.append("【文档类型】").append("report".equals(kind) ? "健康评估报告" : "四周执行计划")
                .append('\n');
        user.append("【分节】").append(title).append('\n');
        user.append("【已确认指标】\n");
        for (CareSectionGenerator.MetricFact metric : metrics) {
            user.append("- ").append(metric.name()).append(' ').append(metric.value());
            if (metric.unit() != null && !metric.unit().isBlank()) {
                user.append(' ').append(metric.unit());
            }
            user.append("（状态：").append(statusText(metric.status())).append("）\n");
        }
        if (context.startDate() != null) {
            user.append("【计划起始日期】").append(context.startDate()).append('\n');
        }
        user.append("【本次撰写要求】").append(sectionRequirement(kind, key));

        try {
            String text = route == null
                    ? llmProvider.chat(system, user.toString())
                    : llmProvider.chat(route, system, user.toString());
            if (text == null || text.isBlank()) {
                return SectionResult.failed("模型返回空内容");
            }
            // metricRefs 由规则算，不信模型自述
            return SectionResult.of(text.strip(), refsFor(kind, metrics));
        } catch (LlmException ex) {
            return SectionResult.failed("模型调用失败: " + ex.getMessage());
        }
    }

    /** 分节写作要求。报告分节与计划分节的要求不同，不能混用。 */
    private String sectionRequirement(String kind, String key) {
        if ("report".equals(kind)) {
            return switch (key) {
                case "profile" -> "概述受检者基本信息与本次纳入的指标总体情况。";
                case "modern_assessment" -> "逐项说明超出参考区间的指标及其意义。";
                case "tcm_assessment" -> "说明现有资料不足以进行中医辨证，并给出就诊建议。";
                case "diet" -> "给出可执行的饮食建议，分为针对异常指标的部分与通用部分。";
                case "exercise" -> "给出可执行的运动建议，包含频次、强度与安全提示。";
                case "rest" -> "给出作息、情绪与生活方式建议。";
                default -> "撰写该分节正文。";
            };
        }
        return switch (key) {
            case "framework" -> "说明四周计划的总体目标、递进逻辑与执行原则。";
            default -> "说明本周主题与本周目标，强调可执行与可打卡。";
        };
    }

    private List<String> refsFor(String kind, List<CareSectionGenerator.MetricFact> metrics) {
        if ("report".equals(kind)) {
            return metrics.stream()
                    .filter(m -> "HIGH".equals(m.status()) || "LOW".equals(m.status()))
                    .map(CareSectionGenerator.MetricFact::code)
                    .distinct()
                    .toList();
        }
        return metrics.stream().map(CareSectionGenerator.MetricFact::code).distinct().toList();
    }

    private static String statusText(String status) {
        if ("HIGH".equals(status)) {
            return "偏高";
        }
        if ("LOW".equals(status)) {
            return "偏低";
        }
        if ("NORMAL".equals(status)) {
            return "正常";
        }
        return "未判定";
    }

    /** 供测试与日志查看：把指标列表渲染成提示词里的文本形式。 */
    static String renderMetrics(List<CareSectionGenerator.MetricFact> metrics) {
        return metrics.stream()
                .map(m -> m.code() + "=" + m.value() + m.unit())
                .collect(Collectors.joining(", ", "[", "]"));
    }

    /** 仅用于把空列表转成可变列表，避免调用方拿到不可变集合。 */
    static List<String> mutable(List<String> source) {
        return new ArrayList<>(source);
    }
}
