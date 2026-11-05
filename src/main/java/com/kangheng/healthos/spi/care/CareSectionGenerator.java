package com.kangheng.healthos.spi.care;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 报告/计划分节生成器。
 *
 * <p>对应原工程 {@code care_worker} 里"每个分节调一次模型"的调用单元，
 * 但把"调模型"抽象成接口，规则实现优先。
 *
 * <p><b>为什么不让模型直接生成整份报告</b>：一份报告有 6 节、每节都要带
 * {@code metricRefs}（结论依据哪些体检指标）。整份生成时，模型很容易
 * 在某一节里写出没有指标支撑的结论——这在健康评估里是硬伤。
 * 拆成"一节一次"，可以让规则实现保证「每个结论都能指回具体指标」，
 * 模型实现则只负责措辞。
 */
public interface CareSectionGenerator {

    /** 生成器名，落进文档的生成记录里，便于回溯"这份报告是谁写的"。 */
    String name();

    /**
     * 生成一个分节。
     *
     * @param kind      report / plan
     * @param key       分节 key
     * @param context   生成上下文：来源体检快照、已生成的分节等
     * @return 分节内容；生成不了就返回 {@link SectionResult#failed}
     */
    SectionResult generate(String kind, String key, GenerationContext context);

    /**
     * 带业务主线的生成入口。
     *
     * <p>调用方传文档 ID，实现按 {@code REPORT} / {@code HEALTH_PLAN} 主线取该文档冻结的
     * 那一版模型配置。同一份报告的 6 个分节共用同一个 bizId，因此用的是同一版配置——
     * 不会出现前半份和后半份来自两个模型。默认转发，让规则实现保持不变。
     */
    default SectionResult generate(com.kangheng.healthos.spi.ai.LlmProvider.RouteContext route,
                                   String kind, String key, GenerationContext context) {
        return generate(kind, key, context);
    }

    /**
     * 生成上下文。
     *
     * @param sourceSnapshot 来源体检的不可变快照
     * @param confirmedMetrics 已确认的指标（只含状态为 CONFIRMED 的）
     * @param existingSections 已生成的分节（前面几节的产出）
     * @param startDate 计划起始日期
     */
    record GenerationContext(Map<String, Object> sourceSnapshot,
                             List<MetricFact> confirmedMetrics,
                             Map<String, Object> existingSections,
                             String startDate) {
    }

    /**
     * 一项已确认的指标。
     *
     * @param code   指标码
     * @param name   指标名
     * @param value  数值
     * @param unit   单位
     * @param status NORMAL / HIGH / LOW
     */
    record MetricFact(String code, String name, String value, String unit, String status) {
    }

    /**
     * 分节生成结果。
     *
     * @param text      正文
     * @param metricRefs 结论依据的指标码列表；空列表表示这一节没有指标支撑
     * @param success   是否生成成功
     *
     * <p><b>为什么要有显式的 success 标志</b>：失败时 {@code text} 里装的是失败原因
     * （比如"未知的报告分节: xxx"），文本非空但它不是内容。如果只用"文本非空"来判断成功，
     * 失败原因会被当成正文写进报告——那是一份看起来完整、实际内容错误的报告。
     */
    record SectionResult(String text, List<String> metricRefs, boolean success) {

        public static SectionResult of(String text, List<String> metricRefs) {
            boolean ok = text != null && !text.isBlank();
            return new SectionResult(text, metricRefs == null ? List.of() : List.copyOf(metricRefs), ok);
        }

        public static SectionResult failed(String reason) {
            return new SectionResult(reason, List.of(), false);
        }

        public boolean ok() {
            return success;
        }
    }

    /**
     * 把数据库里取出的指标行转成 {@link MetricFact}。
     *
     * <p><b>为什么放在接口上而不是某个实现里</b>：这是<b>契约的一部分</b>——
     * 调用方必须按契约构造入参。如果把转换放在规则实现上，业务层为了造参数
     * 就得 {@code import} 一个具体实现类，"业务只依赖接口"那条约束当场失效。
     *
     * <p>容忍缺 {@code code} / {@code name} 的行：直接跳过而不是造一个半残的
     * {@code MetricFact}——没有指标码的"指标"无法被 {@code metricRefs} 引用，
     * 留着只会让报告里出现一条查不到出处的结论。
     */
    static List<MetricFact> toFacts(List<Map<String, Object>> metrics) {
        List<MetricFact> facts = new ArrayList<>();
        if (metrics == null) {
            return facts;
        }
        for (Map<String, Object> metric : metrics) {
            String code = text(metric.get("code"));
            String name = text(metric.get("name"));
            if (code == null || name == null) {
                continue;
            }
            facts.add(new MetricFact(code, name,
                    text(metric.get("value")), text(metric.get("unit")),
                    text(metric.get("status"))));
        }
        return facts;
    }

    /** 取字符串并去空白；空串按"没有值"处理，不返回 {@code ""} 让下游误判。 */
    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().strip();
        return text.isEmpty() ? null : text;
    }
}
