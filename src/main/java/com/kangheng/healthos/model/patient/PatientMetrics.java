package com.kangheng.healthos.model.patient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 体检指标视图的构造与派生计算。
 *
 * <p><b>为什么在 model 而不在抽取器里</b>：这些是<b>指标这个东西本身</b>的构造规则
 * （含越界判定的口径），不是"怎么从文本里抽出指标"。规则抽取器要用它，
 * 业务层建档时算派生 BMI 也要用它——放在任一侧都会让另一侧反向依赖。
 *
 * <p>统一在这里构造还有一个好处：{@code referenceLow}/{@code referenceHigh}/
 * {@code status} 三个字段的写法只有一处，不会出现"抽取器写一套、建档又写一套"
 * 导致同一个指标在不同链路上状态不一致。
 */
public final class PatientMetrics {

    private PatientMetrics() {
    }

    /**
     * 组装一个指标视图，越界判定复用 {@link MetricEvaluator} 同一套规则。
     *
     * <p>用 {@link LinkedHashMap} 保持字段顺序：这个 map 会直接落成 JSON，
     * 顺序稳定便于对账与人工核对。
     */
    public static Map<String, Object> metric(String code, String name, BigDecimal value, String unit,
                                             BigDecimal low, BigDecimal high) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("name", name);
        item.put("value", value);
        item.put("unit", unit);
        item.put("referenceLow", low);
        item.put("referenceHigh", high);
        item.put("status", MetricEvaluator.evaluate(value, low, high).name());
        return item;
    }

    /**
     * 由身高体重算 BMI。
     *
     * <p>BMI 是派生指标，不是原文里写的，所以它不进"抽取"结果，
     * 而是在汇总阶段算出来——这样能看出它是从哪两个字段推出来的。
     *
     * <p>身高按厘米传入，先转米再算；身高非正数返回 {@code null}（除零保护），
     * 缺任一输入也返回 {@code null}——不猜。
     */
    public static BigDecimal bmi(BigDecimal heightCm, BigDecimal weightKg) {
        if (heightCm == null || weightKg == null) {
            return null;
        }
        BigDecimal meters = heightCm.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
        if (meters.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return weightKg.divide(meters.multiply(meters), 2, RoundingMode.HALF_UP);
    }
}
