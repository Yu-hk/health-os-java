package com.kangheng.healthos.model.patient;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricEvaluatorTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    @DisplayName("区间端点为闭区间")
    void boundariesAreInclusive() {
        assertThat(MetricEvaluator.evaluate(d("3.9"), d("3.9"), d("6.1")))
                .isEqualTo(MetricEvaluator.Status.NORMAL);
        assertThat(MetricEvaluator.evaluate(d("6.1"), d("3.9"), d("6.1")))
                .isEqualTo(MetricEvaluator.Status.NORMAL);
    }

    @Test
    @DisplayName("越界判定方向正确")
    void detectsOutOfRange() {
        assertThat(MetricEvaluator.evaluate(d("3.89"), d("3.9"), d("6.1")))
                .isEqualTo(MetricEvaluator.Status.LOW);
        assertThat(MetricEvaluator.evaluate(d("6.11"), d("3.9"), d("6.1")))
                .isEqualTo(MetricEvaluator.Status.HIGH);
    }

    @Test
    @DisplayName("只有单侧参考值时不猜测另一侧")
    void handlesSingleSidedReference() {
        assertThat(MetricEvaluator.evaluate(d("9"), null, d("6.1")))
                .isEqualTo(MetricEvaluator.Status.HIGH);
        assertThat(MetricEvaluator.evaluate(d("2"), null, d("6.1")))
                .isEqualTo(MetricEvaluator.Status.NORMAL);
        assertThat(MetricEvaluator.evaluate(d("2"), d("3.9"), null))
                .isEqualTo(MetricEvaluator.Status.LOW);
    }

    @Test
    @DisplayName("缺参考范围或数值时返回 UNKNOWN")
    void unknownWithoutReference() {
        assertThat(MetricEvaluator.evaluate(d("5"), null, null)).isEqualTo(MetricEvaluator.Status.UNKNOWN);
        assertThat(MetricEvaluator.evaluate(null, d("3.9"), d("6.1"))).isEqualTo(MetricEvaluator.Status.UNKNOWN);
    }

    @Test
    @DisplayName("风险等级按最严重指标推导")
    void derivesRiskLevel() {
        assertThat(MetricEvaluator.riskLevel(List.of(MetricEvaluator.Status.NORMAL,
                MetricEvaluator.Status.HIGH))).isEqualTo("HIGH");
        assertThat(MetricEvaluator.riskLevel(List.of(MetricEvaluator.Status.NORMAL,
                MetricEvaluator.Status.LOW))).isEqualTo("MEDIUM");
        assertThat(MetricEvaluator.riskLevel(List.of(MetricEvaluator.Status.NORMAL))).isEqualTo("LOW");
    }

    @Test
    @DisplayName("无有效指标时风险等级为 UNKNOWN")
    void unknownRiskLevel() {
        assertThat(MetricEvaluator.riskLevel(List.of())).isEqualTo("UNKNOWN");
        assertThat(MetricEvaluator.riskLevel(null)).isEqualTo("UNKNOWN");
        assertThat(MetricEvaluator.riskLevel(List.of(MetricEvaluator.Status.UNKNOWN))).isEqualTo("UNKNOWN");
    }
}
