package com.kangheng.healthos.service.classics;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 分段重试策略。
 *
 * <p>决策顺序（与原项目 {@code _retry_delay_seconds} / {@code _should_split_immediately} 一致）：
 * <ol>
 *   <li>体量敏感 → 直接二分，不浪费一次重试；</li>
 *   <li>不可重试 → 立即失败；</li>
 *   <li>超过最大尝试次数 → 结束，交给上层判定；</li>
 *   <li>其余 → 指数退避后重试。</li>
 * </ol>
 */
public final class RetryPolicy {

    private RetryPolicy() {
    }

    public enum Action {
        RETRY,
        SPLIT,
        FAIL
    }

    public record Decision(Action action, long delayMillis, String reason) {

        public static Decision retry(long delayMillis) {
            return new Decision(Action.RETRY, delayMillis, "指数退避重试");
        }

        public static Decision split(String reason) {
            return new Decision(Action.SPLIT, 0, reason);
        }

        public static Decision fail(String reason) {
            return new Decision(Action.FAIL, 0, reason);
        }
    }

    public static Decision decide(int failedAttempt,
                                  boolean retryable,
                                  boolean sizeSensitive,
                                  int maxAttempts,
                                  long baseMillis,
                                  long maxMillis,
                                  int depth,
                                  int maxSplitDepth) {
        if (sizeSensitive && depth < maxSplitDepth) {
            return Decision.split("分段体量敏感，二分后重跑");
        }
        if (!retryable) {
            return Decision.fail("不可重试的模型错误");
        }
        if (failedAttempt >= maxAttempts) {
            return Decision.fail("已达最大尝试次数 " + maxAttempts);
        }
        return Decision.retry(backoffMillis(failedAttempt, baseMillis, maxMillis));
    }

    /** 纯函数退避：base * 2^(attempt-1)，封顶 max。不做抖动，便于断言。 */
    public static long backoffMillis(int failedAttempt, long baseMillis, long maxMillis) {
        int exponent = Math.min(Math.max(failedAttempt - 1, 0), 20);
        long raw = baseMillis * (1L << exponent);
        return Math.min(raw, maxMillis);
    }

    /** 生产路径在此叠加抖动，避免多个任务在同一时刻集中重试。 */
    public static long jittered(long millis, double ratio) {
        if (millis <= 0 || ratio <= 0) {
            return millis;
        }
        long spread = (long) (millis * ratio);
        if (spread <= 0) {
            return millis;
        }
        long delta = ThreadLocalRandom.current().nextLong(-spread, spread + 1);
        return Math.max(0, millis + delta);
    }
}
