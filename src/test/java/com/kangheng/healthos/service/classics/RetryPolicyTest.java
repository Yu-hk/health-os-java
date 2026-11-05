package com.kangheng.healthos.service.classics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private static final long BASE = 1_000L;
    private static final long MAX = 15_000L;

    @Test
    @DisplayName("体量敏感错误优先二分，而不是浪费一次重试")
    void sizeSensitiveSplitsFirst() {
        RetryPolicy.Decision decision = RetryPolicy.decide(1, true, true, 2, BASE, MAX, 0, 3);
        assertThat(decision.action()).isEqualTo(RetryPolicy.Action.SPLIT);
    }

    @Test
    @DisplayName("达到最大二分深度后退化为普通重试")
    void stopsSplittingAtMaxDepth() {
        RetryPolicy.Decision decision = RetryPolicy.decide(1, true, true, 2, BASE, MAX, 3, 3);
        assertThat(decision.action()).isEqualTo(RetryPolicy.Action.RETRY);
    }

    @Test
    @DisplayName("不可重试错误立即失败")
    void nonRetryableFailsImmediately() {
        RetryPolicy.Decision decision = RetryPolicy.decide(1, false, false, 3, BASE, MAX, 0, 3);
        assertThat(decision.action()).isEqualTo(RetryPolicy.Action.FAIL);
    }

    @Test
    @DisplayName("用尽尝试次数后失败")
    void failsAfterMaxAttempts() {
        RetryPolicy.Decision decision = RetryPolicy.decide(2, true, false, 2, BASE, MAX, 0, 3);
        assertThat(decision.action()).isEqualTo(RetryPolicy.Action.FAIL);
    }

    @Test
    @DisplayName("退避按指数增长并封顶")
    void backoffGrowsExponentiallyAndCaps() {
        assertThat(RetryPolicy.backoffMillis(1, BASE, MAX)).isEqualTo(1_000L);
        assertThat(RetryPolicy.backoffMillis(2, BASE, MAX)).isEqualTo(2_000L);
        assertThat(RetryPolicy.backoffMillis(3, BASE, MAX)).isEqualTo(4_000L);
        assertThat(RetryPolicy.backoffMillis(4, BASE, MAX)).isEqualTo(8_000L);
        assertThat(RetryPolicy.backoffMillis(5, BASE, MAX)).isEqualTo(15_000L);
        assertThat(RetryPolicy.backoffMillis(30, BASE, MAX)).isEqualTo(15_000L);
    }

    @Test
    @DisplayName("抖动后仍在可接受区间内且不为负")
    void jitterStaysWithinRange() {
        for (int i = 0; i < 200; i++) {
            long value = RetryPolicy.jittered(10_000L, 0.25);
            assertThat(value).isBetween(7_500L, 12_500L);
        }
        assertThat(RetryPolicy.jittered(0L, 0.25)).isZero();
        assertThat(RetryPolicy.jittered(1_000L, 0)).isEqualTo(1_000L);
    }
}
