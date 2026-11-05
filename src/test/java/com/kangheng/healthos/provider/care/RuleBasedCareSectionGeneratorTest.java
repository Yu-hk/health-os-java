package com.kangheng.healthos.provider.care;

import java.util.List;
import java.util.Map;

import com.kangheng.healthos.contract.care.CareContract;
import com.kangheng.healthos.spi.care.CareSectionGenerator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则生成器的核心约束：<b>只用已确认指标说话，没依据就明说没依据</b>。
 *
 * <p>这一组用例守的是内容正确性，不是格式。医疗场景里，一句凭空捏造的
 * "中医辨证为脾虚湿盛"会被患者当真，比一句"资料不足需面诊"危险得多。
 */
class RuleBasedCareSectionGeneratorTest {

    private final RuleBasedCareSectionGenerator generator = new RuleBasedCareSectionGenerator();

    private CareSectionGenerator.GenerationContext context(List<CareSectionGenerator.MetricFact> metrics) {
        return new CareSectionGenerator.GenerationContext(
                Map.of("basicInfo", Map.of("name", "张三", "gender", "MALE",
                        "birthDate", "1970-01-01")),
                metrics, Map.of(), "2026-11-10");
    }

    @Test
    @DisplayName("报告 6 个分节全部生成成功，不允许缺节")
    void generatesAllReportSections() {
        CareSectionGenerator.GenerationContext ctx = context(List.of(
                new CareSectionGenerator.MetricFact("FBG", "空腹血糖", "6.2", "mmol/L", "HIGH"),
                new CareSectionGenerator.MetricFact("SBP", "收缩压", "145", "mmHg", "HIGH")));

        for (String key : CareContract.REPORT_KEYS) {
            CareSectionGenerator.SectionResult result = generator.generate("report", key, ctx);
            assertThat(result.ok())
                    .as("分节 %s 应生成成功", key)
                    .isTrue();
            assertThat(result.text().length()).as("分节 %s 正文不应过短", key).isGreaterThan(20);
        }
    }

    @Test
    @DisplayName("计划 5 个分节全部生成成功（总体框架 + 四周）")
    void generatesAllPlanSections() {
        CareSectionGenerator.GenerationContext ctx = context(List.of(
                new CareSectionGenerator.MetricFact("FBG", "空腹血糖", "6.2", "mmol/L", "HIGH")));

        for (String key : CareContract.PLAN_KEYS) {
            CareSectionGenerator.SectionResult result = generator.generate("plan", key, ctx);
            assertThat(result.ok()).as("分节 %s 应生成成功", key).isTrue();
        }
    }

    @Test
    @DisplayName("中医分节不编造证型：没有四诊信息就写明资料不足")
    void refusesToInventTcmDiagnosis() {
        CareSectionGenerator.GenerationContext ctx = context(List.of(
                new CareSectionGenerator.MetricFact("FBG", "空腹血糖", "6.2", "mmol/L", "HIGH")));

        CareSectionGenerator.SectionResult result =
                generator.generate("report", "tcm_assessment", ctx);

        assertThat(result.ok()).isTrue();
        assertThat(result.text()).contains("不进行中医证型判定");
        assertThat(result.text()).contains("面诊");
        // 关键：这一节没有任何指标支撑，metricRefs 必须是空的
        assertThat(result.metricRefs()).isEmpty();
        assertThat(result.text()).doesNotContain("脾虚").doesNotContain("湿盛").doesNotContain("肾阴虚");
    }

    @Test
    @DisplayName("现代医学评估逐项列出偏离指标，并带上指标依据")
    void listsAbnormalMetricsWithEvidence() {
        CareSectionGenerator.GenerationContext ctx = context(List.of(
                new CareSectionGenerator.MetricFact("FBG", "空腹血糖", "6.2", "mmol/L", "HIGH"),
                new CareSectionGenerator.MetricFact("TC", "总胆固醇", "4.8", "mmol/L", "NORMAL"),
                new CareSectionGenerator.MetricFact("UA", "尿酸", "480", "μmol/L", "HIGH")));

        CareSectionGenerator.SectionResult result =
                generator.generate("report", "modern_assessment", ctx);

        assertThat(result.text()).contains("空腹血糖").contains("尿酸").contains("偏高");
        assertThat(result.text()).doesNotContain("总胆固醇");
        assertThat(result.metricRefs()).containsExactlyInAnyOrder("FBG", "UA");
    }

    @Test
    @DisplayName("全部指标正常时不制造异常结论")
    void doesNotInventAbnormality() {
        CareSectionGenerator.GenerationContext ctx = context(List.of(
                new CareSectionGenerator.MetricFact("FBG", "空腹血糖", "5.2", "mmol/L", "NORMAL")));

        CareSectionGenerator.SectionResult result =
                generator.generate("report", "modern_assessment", ctx);

        assertThat(result.text()).contains("均处于参考区间内");
        assertThat(result.text()).doesNotContain("偏高").doesNotContain("偏低");
    }

    @Test
    @DisplayName("饮食建议引用真实存在的指标码，不引用不存在的指标")
    void referencesOnlyRealMetricCodes() {
        CareSectionGenerator.GenerationContext ctx = context(List.of(
                new CareSectionGenerator.MetricFact("FBG", "空腹血糖", "6.2", "mmol/L", "HIGH"),
                new CareSectionGenerator.MetricFact("UA", "尿酸", "480", "μmol/L", "HIGH")));

        CareSectionGenerator.SectionResult result = generator.generate("report", "diet", ctx);

        assertThat(result.metricRefs()).containsExactlyInAnyOrder("FBG", "UA");
        for (String code : result.metricRefs()) {
            assertThat(code).isIn("FBG", "UA");
        }
    }

    @Test
    @DisplayName("没有已确认指标时，不生成任何有依据的结论")
    void producesNoClaimsWithoutMetrics() {
        CareSectionGenerator.GenerationContext ctx = context(List.of());

        CareSectionGenerator.SectionResult result =
                generator.generate("report", "modern_assessment", ctx);

        assertThat(result.metricRefs()).isEmpty();
    }

    @Test
    @DisplayName("未知分节明确报错，不返回空内容蒙混过关")
    void failsOnUnknownSection() {
        CareSectionGenerator.SectionResult result = generator.generate("report", "unknown_key",
                context(List.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.text()).contains("未知的报告分节");
    }

    @Test
    @DisplayName("建议不串节：饮食/运动/作息各自只出现本类建议")
    void adviceStaysInItsOwnTrack() {
        // 这四个指标覆盖了饮食（FBG/BMI）、运动（BMI）与作息（DBP）三类建议，
        // 最容易暴露"同一批建议被复制到三个分节"的问题。
        CareSectionGenerator.GenerationContext ctx = context(List.of(
                new CareSectionGenerator.MetricFact("FBG", "空腹血糖", "6.8", "mmol/L", "HIGH"),
                new CareSectionGenerator.MetricFact("BMI", "体质指数", "25.1", "kg/m²", "HIGH"),
                new CareSectionGenerator.MetricFact("DBP", "舒张压", "96", "mmHg", "HIGH")));

        String diet = generator.generate("report", "diet", ctx).text();
        String exercise = generator.generate("report", "exercise", ctx).text();
        String rest = generator.generate("report", "rest", ctx).text();

        // 饮食节不谈训练安排
        assertThat(diet).doesNotContain("抗阻训练").doesNotContain("有氧运动");
        // 运动节不谈吃什么
        assertThat(exercise).doesNotContain("全谷物").doesNotContain("膳食纤维");
        // 作息节不谈饮食与运动处方——这条正是修复前会出错的地方
        assertThat(rest).doesNotContain("全谷物").doesNotContain("深海鱼").doesNotContain("有氧运动");
        // 但本类建议必须在：作息节要能看到"减少熬夜"
        assertThat(rest).contains("减少熬夜与睡眠不足");
        // 运动节要能看到抗阻训练
        assertThat(exercise).contains("抗阻训练");
    }
}
