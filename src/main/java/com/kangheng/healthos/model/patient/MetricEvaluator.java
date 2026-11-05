package com.kangheng.healthos.model.patient;

import java.math.BigDecimal;
import java.util.List;

/**
 * 指标越界判定与风险分级。纯函数，便于单元测试覆盖边界值。
 *
 * <p><b>为什么放在 model 而不是 service</b>：这是<b>值对象自身的分类逻辑</b>
 * （给定数值与参考区间，判定 LOW/NORMAL/HIGH），不是业务编排。它同时被
 * 业务层（{@code PatientService}）与适配层（规则抽取器）使用——放在
 * {@code service} 会让适配层反向依赖业务层，放在 model 才是两边都能依赖的公共层。
 */
public final class MetricEvaluator {

    private MetricEvaluator() {
    }

    public enum Status {
        LOW,
        NORMAL,
        HIGH,
        UNKNOWN
    }

    /**
     * 区间为闭区间；缺任一端的处理：
     * 只有下限时低于下限为 LOW，其余 NORMAL；只有上限时高于上限为 HIGH，其余 NORMAL；
     * 两端都缺则 UNKNOWN——不猜测参考范围。
     */
    public static Status evaluate(BigDecimal value, BigDecimal low, BigDecimal high) {
        if (value == null) {
            return Status.UNKNOWN;
        }
        if (low == null && high == null) {
            return Status.UNKNOWN;
        }
        if (low != null && value.compareTo(low) < 0) {
            return Status.LOW;
        }
        if (high != null && value.compareTo(high) > 0) {
            return Status.HIGH;
        }
        return Status.NORMAL;
    }

    /**
     * 由指标状态推导患者风险等级：任一 HIGH → HIGH；仅 LOW → MEDIUM；全 NORMAL → LOW；
     * 无有效指标 → UNKNOWN。
     */
    public static String riskLevel(List<Status> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return "UNKNOWN";
        }
        boolean known = false;
        boolean anyHigh = false;
        boolean anyLow = false;
        for (Status status : statuses) {
            if (status == null || status == Status.UNKNOWN) {
                continue;
            }
            known = true;
            if (status == Status.HIGH) {
                anyHigh = true;
            }
            if (status == Status.LOW) {
                anyLow = true;
            }
        }
        if (!known) {
            return "UNKNOWN";
        }
        if (anyHigh) {
            return "HIGH";
        }
        return anyLow ? "MEDIUM" : "LOW";
    }
}
