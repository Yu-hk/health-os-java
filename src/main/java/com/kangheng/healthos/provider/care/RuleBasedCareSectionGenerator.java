package com.kangheng.healthos.provider.care;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kangheng.healthos.contract.care.CareContract;
import com.kangheng.healthos.spi.care.CareSectionGenerator;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 规则生成器：只用已确认的指标拼内容，不做任何推断。
 *
 * <p><b>设计原则：宁可说"不知道"，也不编一段像模像样的结论。</b>
 * 健康评估里，一句没有依据的"脾虚湿盛"比一句"需中医师面诊辨证"危险得多——
 * 前者会被患者当真。所以：
 * <ul>
 *   <li>有指标支撑的（现代医学评估、饮食/运动/作息建议）才写具体结论，
 *       并把依据的指标码记进 {@code metricRefs}；</li>
 *   <li>没有指标能支撑的（中医辨证），如实写"资料不足以辨证，需面诊"，
 *       {@code metricRefs} 留空——空引用在这里是诚实，不是缺陷。</li>
 * </ul>
 *
 * <p>模型实现（{@link LlmCareSectionGenerator}）负责把同样这些事实写得更专业，
 * 但它拿到的输入也只有这里的 {@code confirmedMetrics}，没有额外的"知识"。
 */
@Component
@ConditionalOnProperty(name = "healthos.care.generator", havingValue = "rule", matchIfMissing = true)
public class RuleBasedCareSectionGenerator implements CareSectionGenerator {

    /**
     * 指标码 → 建议要点。
     *
     * <p>每条建议都标了归属分节（饮食 / 运动 / 作息）。这不是排版偏好：
     * 同一批建议如果同时塞进三个分节，就会出现"作息建议：增加全谷物与膳食纤维"
     * 这种张冠李戴的结论——读者是按分节执行的，写错地方就是错的行为指导。
     *
     * <p>写死在表里而不是让模型自由发挥，是为了保证每条建议都能指回一个确证指标。
     */
    private static final Map<String, List<Advice>> METRIC_ADVICE = metricAdvice();

    /** 建议归属的分节。{@code ASSESS} 是复查/监测/用药类，归入评估与通用建议。 */
    private enum Track { DIET, EXERCISE, REST, ASSESS }

    private record Advice(String text, Track track) {
    }

    private static Map<String, List<Advice>> metricAdvice() {
        Map<String, List<Advice>> map = new LinkedHashMap<>();
        map.put("FBG", List.of(
                new Advice("规律进餐，控制精制糖与主食总量", Track.DIET),
                new Advice("增加全谷物与膳食纤维", Track.DIET),
                new Advice("餐后适度活动，避免长时间静坐", Track.EXERCISE)));
        map.put("PPG", List.of(
                new Advice("调整进餐顺序，先菜后主食", Track.DIET),
                new Advice("控制单餐总量", Track.DIET),
                new Advice("餐后散步 10–15 分钟", Track.EXERCISE)));
        map.put("HbA1c", List.of(
                new Advice("按医嘱定期复查糖化血红蛋白", Track.ASSESS),
                new Advice("保持每日主食与蛋白质配比稳定", Track.DIET)));
        map.put("TG", List.of(
                new Advice("限制精制碳水与含糖饮料", Track.DIET),
                new Advice("增加深海鱼与坚果", Track.DIET),
                new Advice("严格限制酒精", Track.DIET)));
        map.put("TC", List.of(
                new Advice("减少饱和脂肪与反式脂肪摄入", Track.DIET),
                new Advice("增加可溶性膳食纤维", Track.DIET),
                new Advice("每周安排 2 次深海鱼", Track.DIET)));
        map.put("LDL", List.of(
                new Advice("增加燕麦、豆类等可溶性纤维食物", Track.DIET),
                new Advice("控制动物内脏与奶油制品", Track.DIET),
                new Advice("维持规律有氧运动", Track.EXERCISE)));
        map.put("HDL", List.of(
                new Advice("保持每周中等强度有氧运动", Track.EXERCISE),
                new Advice("减少吸烟与过量饮酒", Track.REST)));
        map.put("SBP", List.of(
                new Advice("每日食盐控制在 5 克以内", Track.DIET),
                new Advice("规律监测血压并记录", Track.ASSESS),
                new Advice("保持规律有氧运动，避免憋气发力", Track.EXERCISE)));
        map.put("DBP", List.of(
                new Advice("限制腌制与加工食品", Track.DIET),
                new Advice("减少熬夜与睡眠不足", Track.REST),
                new Advice("保持情绪稳定，必要时寻求心理支持", Track.REST)));
        map.put("ALT", List.of(
                new Advice("严格戒酒", Track.DIET),
                new Advice("避免熬夜与肝毒性药物自行使用", Track.REST),
                new Advice("复查肝功能并按医嘱随访", Track.ASSESS)));
        map.put("AST", List.of(
                new Advice("严格戒酒", Track.DIET),
                new Advice("控制高脂饮食", Track.DIET),
                new Advice("复查肝功能并按医嘱随访", Track.ASSESS)));
        map.put("Cr", List.of(
                new Advice("保证日常饮水量", Track.DIET),
                new Advice("避免自行使用非甾体止痛药", Track.ASSESS),
                new Advice("按医嘱复查肾功能", Track.ASSESS)));
        map.put("UA", List.of(
                new Advice("限制高嘌呤食物（动物内脏、浓汤、部分海鲜）", Track.DIET),
                new Advice("增加饮水量，必要时按医嘱使用降尿酸药物", Track.DIET)));
        map.put("BMI", List.of(
                new Advice("每周安排 150 分钟以上中等强度有氧运动", Track.EXERCISE),
                new Advice("控制总能量摄入，逐步减重", Track.DIET),
                new Advice("增加抗阻训练，每周 2 次", Track.EXERCISE)));
        map.put("BMI_HIGH", List.of(
                new Advice("每周安排 150 分钟以上中等强度有氧运动", Track.EXERCISE),
                new Advice("控制总能量摄入，逐步减重", Track.DIET),
                new Advice("增加抗阻训练，每周 2 次", Track.EXERCISE)));
        map.put("BMI_LOW", List.of(
                new Advice("保证每日足量优质蛋白摄入", Track.DIET),
                new Advice("减少空腹与不规律进食", Track.DIET),
                new Advice("在专业指导下增加力量训练", Track.EXERCISE)));
        return Map.copyOf(map);
    }

    @Override
    public String name() {
        return "rule-based";
    }

    @Override
    public SectionResult generate(String kind, String key, GenerationContext context) {
        if ("report".equals(kind)) {
            return reportSection(key, context);
        }
        if ("plan".equals(kind)) {
            return planSection(key, context);
        }
        return SectionResult.failed("未知的文档类型: " + kind);
    }

    // ------------------------------------------------------------ 报告

    private SectionResult reportSection(String key, GenerationContext context) {
        List<MetricFact> metrics = context.confirmedMetrics();
        switch (key) {
            case "profile":
                return profile(context);
            case "modern_assessment":
                return modernAssessment(metrics);
            case "tcm_assessment":
                // 中医辨证需要望闻问切，体检数据里没有这些信息。
                // 这里明确写"资料不足以辨证"，不给任何证型——这是最诚实也最安全的处理。
                return SectionResult.of(
                        "本次评估依据的资料仅包含体检指标与基本信息，不包含望诊、闻诊、切脉"
                                + "等四诊信息，也缺少既往病史、用药史与生活方式问诊记录，"
                                + "因此不进行中医证型判定。"
                                + "建议携带本次报告至中医科面诊，由具备执业资质的中医师结合四诊合参辨证，"
                                + "并据此调整干预方案。",
                        List.of());
            case "diet":
                return adviceSection("饮食干预建议", metrics, Track.DIET,
                        List.of("按膳食宝塔搭配每日三餐，主食、蔬果、肉蛋奶、豆类均衡",
                                "每日饮水 1500–1700 毫升，饮品以白水或淡茶为主"),
                        List.of("限制高油高糖与腌制加工食品", "进食速度放缓，每餐七八分饱"));
            case "exercise":
                return adviceSection("运动干预建议", metrics, Track.EXERCISE,
                        List.of("每周累计 150–300 分钟中等强度有氧运动，可拆分为每日 20–30 分钟",
                                "每周 2 次抗阻训练，每次覆盖主要肌群",
                                "运动前热身、运动后拉伸，避免空腹与带伤运动"),
                        List.of("运动强度以能正常交谈为准", "有慢性病史者运动前咨询医生"));
            case "rest":
                return adviceSection("作息与生活方式建议", metrics, Track.REST,
                        List.of("保持规律作息，成人每日睡眠 7–8 小时",
                                "限制屏幕使用时间，睡前 1 小时避免强光与刺激性内容",
                                "戒烟限酒，避免二手烟"),
                        List.of("保持情绪稳定，长期压力会通过睡眠与内分泌影响代谢指标",
                                "建议定期复查异常指标并记录变化"));
            default:
                return SectionResult.failed("未知的报告分节: " + key);
        }
    }

    private SectionResult profile(GenerationContext context) {
        List<MetricFact> metrics = context.confirmedMetrics();
        Map<String, Object> snapshot = context.sourceSnapshot() == null
                ? Map.of() : context.sourceSnapshot();
        Object basic = snapshot.get("basicInfo");
        Map<String, Object> basicInfo = basic instanceof Map<?, ?> map ? castMap(map) : Map.of();

        StringBuilder text = new StringBuilder();
        text.append("本次健康画像基于已通过两道人工核查的体检资料生成，共纳入 ")
                .append(metrics.size()).append(" 项已确认指标。");
        String name = textOf(basicInfo.get("name"));
        if (name != null) {
            text.append("受检者：").append(name).append('。');
        }
        Object gender = basicInfo.get("gender");
        if (gender != null && !gender.toString().isBlank()) {
            text.append("性别：").append(gender).append('。');
        }
        Object birthDate = basicInfo.get("birthDate");
        if (birthDate != null && !birthDate.toString().isBlank()) {
            text.append("出生日期：").append(birthDate).append('。');
        }

        List<String> abnormal = metrics.stream()
                .filter(m -> "HIGH".equals(m.status()) || "LOW".equals(m.status()))
                .map(MetricFact::name).toList();
        if (abnormal.isEmpty()) {
            text.append("已确认指标全部在参考区间内，未见异常项。");
        } else {
            text.append("其中 ").append(abnormal.size()).append(" 项超出参考区间：")
                    .append(String.join("、", abnormal)).append("。");
        }
        text.append("本画像仅反映上述已确认数据，不包含未提供的检查项目，也不构成诊断。");

        List<String> refs = new ArrayList<>();
        for (MetricFact metric : metrics) {
            refs.add(metric.code());
        }
        return SectionResult.of(text.toString(), refs);
    }

    private SectionResult modernAssessment(List<MetricFact> metrics) {
        List<MetricFact> abnormal = metrics.stream()
                .filter(m -> "HIGH".equals(m.status()) || "LOW".equals(m.status()))
                .toList();
        if (abnormal.isEmpty()) {
            return SectionResult.of(
                    "本次评估范围内的 " + metrics.size()
                            + " 项已确认指标均处于参考区间内，未发现需要重点干预的偏离项。"
                            + "建议维持当前生活方式，并按常规体检周期复查。",
                    metrics.stream().map(MetricFact::code).toList());
        }
        StringBuilder text = new StringBuilder();
        text.append("本次评估发现 ").append(abnormal.size()).append(" 项指标超出参考区间，逐项说明如下：\n");
        List<String> refs = new ArrayList<>();
        for (MetricFact metric : abnormal) {
            String direction = "HIGH".equals(metric.status()) ? "偏高" : "偏低";
            text.append("· ").append(metric.name()).append(' ').append(metric.value());
            if (metric.unit() != null && !metric.unit().isBlank()) {
                text.append(' ').append(metric.unit());
            }
            text.append("（").append(direction).append("）。");
            List<Advice> advice = METRIC_ADVICE.get(metric.code());
            if (advice != null && !advice.isEmpty()) {
                text.append("建议：").append(advice.get(0).text()).append('。');
            }
            text.append('\n');
            refs.add(metric.code());
        }
        text.append("以上结论仅基于本次已确认的体检指标，不包含影像、检验以外的临床资料，"
                + "不能替代医师的完整评估；如出现不适或指标持续异常，请及时就诊。");
        return SectionResult.of(text.toString(), refs);
    }

    private SectionResult adviceSection(String title, List<MetricFact> metrics, Track track,
                                        List<String> baseline, List<String> general) {
        StringBuilder text = new StringBuilder();
        text.append(title).append('\n');

        Set<String> codes = new LinkedHashSet<>();
        List<String> specific = new ArrayList<>();
        for (MetricFact metric : metrics) {
            if (!"HIGH".equals(metric.status()) && !"LOW".equals(metric.status())) {
                continue;
            }
            List<Advice> advice = METRIC_ADVICE.get(metric.code());
            if (advice == null) {
                continue;
            }
            boolean referenced = false;
            for (int i = 0; i < advice.size(); i++) {
                Advice item = advice.get(i);
                // 第一条已在 modern_assessment 里说过；也不把别的分节的建议搬过来。
                if (i == 0 || item.track() != track) {
                    continue;
                }
                String line = "针对" + metric.name() + "：" + item.text() + "。";
                if (!specific.contains(line)) {
                    specific.add(line);
                }
                referenced = true;
            }
            if (referenced) {
                codes.add(metric.code());
            }
        }
        if (!specific.isEmpty()) {
            text.append("一、结合本次异常指标的针对性建议\n");
            for (String line : specific) {
                text.append("· ").append(line).append('\n');
            }
            text.append('\n');
        }
        text.append(specific.isEmpty() ? "一、通用建议\n" : "二、通用建议\n");
        for (String item : baseline) {
            text.append("· ").append(item).append('\n');
        }
        for (String item : general) {
            text.append("· ").append(item).append('\n');
        }
        text.append("以上建议为健康管理层面的生活方式指导，具体用药与治疗方案须由执业医师决定。");
        return SectionResult.of(text.toString(), new ArrayList<>(codes));
    }

    // ------------------------------------------------------------ 计划

    private SectionResult planSection(String key, GenerationContext context) {
        List<MetricFact> metrics = context.confirmedMetrics();
        if ("framework".equals(key)) {
            StringBuilder text = new StringBuilder();
            text.append("四周计划以本次已确认的体检指标为依据，围绕");
            List<String> targets = metrics.stream()
                    .filter(m -> "HIGH".equals(m.status()) || "LOW".equals(m.status()))
                    .map(MetricFact::name).toList();
            if (targets.isEmpty()) {
                text.append("维持当前健康状态与巩固现有良好习惯");
            } else {
                text.append("以下重点偏离项的改善").append("：").append(String.join("、", targets));
            }
            text.append("展开，每周设置一个主题，逐周递进。第 1 周建立基础节律，"
                    + "第 2 周强化核心指标相关行为，第 3 周增加训练强度并引入复测，"
                    + "第 4 周巩固并形成可持续的长期方案。\n"
                    + "执行原则：每周至少完成计划中 5 天的打卡；单次未完成的任务顺延而非补做，"
                    + "避免短期内集中加量；出现头晕、胸闷、明显乏力等不适立即停止并就医。");
            return SectionResult.of(text.toString(),
                    metrics.stream().map(MetricFact::code).toList());
        }
        if (key.startsWith("week_")) {
            int week = parseWeek(key);
            if (week < 1 || week > 4) {
                return SectionResult.failed("周次超出范围: " + key);
            }
            String theme = weekTheme(week, metrics);
            StringBuilder text = new StringBuilder();
            text.append("本周主题：").append(theme).append('\n');
            text.append("本周目标：").append(weekGoal(week, metrics)).append('\n');
            text.append("请按下方逐日任务打卡。连续两天未完成时请重新评估负荷，"
                    + "必要时向健康管理人员申请下调强度。");
            List<String> refs = metrics.stream()
                    .filter(m -> "HIGH".equals(m.status()) || "LOW".equals(m.status()))
                    .map(MetricFact::code).toList();
            return SectionResult.of(text.toString(), refs);
        }
        return SectionResult.failed("未知的计划分节: " + key);
    }

    private static int parseWeek(String key) {
        try {
            return Integer.parseInt(key.substring("week_".length()));
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    private String weekTheme(int week, List<MetricFact> metrics) {
        boolean hasWeight = metrics.stream().anyMatch(m -> m.code().startsWith("BMI"));
        boolean hasGlucose = metrics.stream().anyMatch(m -> "FBG".equals(m.code()) || "HbA1c".equals(m.code()));
        boolean hasLipid = metrics.stream().anyMatch(m -> "TG".equals(m.code()) || "TC".equals(m.code())
                || "LDL".equals(m.code()));
        boolean hasBp = metrics.stream().anyMatch(m -> "SBP".equals(m.code()) || "DBP".equals(m.code()));
        boolean hasLiver = metrics.stream().anyMatch(m -> "ALT".equals(m.code()) || "AST".equals(m.code()));

        return switch (week) {
            case 1 -> "建立基础节律（饮食记录、规律作息、日常活动量）";
            case 2 -> hasGlucose ? "血糖与体重管理起步"
                    : hasLipid ? "血脂管理起步" : hasBp ? "血压管理起步" : "巩固基础节律";
            case 3 -> (hasWeight || hasGlucose || hasLipid) ? "有氧与抗阻训练进阶"
                    : hasLiver ? "作息与肝脏保护强化" : "强度进阶与稳定性提升";
            case 4 -> "巩固与复盘（长期方案固化、复测与调整）";
            default -> "执行";
        };
    }

    private String weekGoal(int week, List<MetricFact> metrics) {
        return switch (week) {
            case 1 -> "完成每日三餐记录与 7 小时以上睡眠，累计完成 5 天以上打卡，"
                    + "建立每日 6000 步以上的活动量基线。";
            case 2 -> "在第 1 周基础上，把有氧运动提升到每周 150 分钟，"
                    + "并将精制糖与酒精摄入降到每周 1 次以内，完成一次复测。";
            case 3 -> "每周有氧 180 分钟以上并加入 2 次抗阻训练，"
                    + "关键指标相关行为连续保持 5 天以上，完成第二次复测。";
            case 4 -> "把有效行为固化为日常习惯，识别并处理复发的诱因，"
                    + "完成一次全面复测并形成下一周期方案。";
            default -> "按计划执行";
        };
    }

    // ------------------------------------------------------------ 工具

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String textOf(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().strip();
        return text.isEmpty() ? null : text;
    }
}
