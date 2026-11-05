package com.kangheng.healthos.service.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.config.HealthOsProperties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginThrottleTest {

    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");

    @Test
    @DisplayName("未达阈值的失败不锁定")
    void belowThresholdIsAllowed() {
        TestClock clock = new TestClock(NOW);
        LoginThrottle throttle = throttle(clock, 3, Duration.ofMinutes(10));
        throttle.recordFailure("alice");
        throttle.recordFailure("alice");
        assertThatCode(() -> throttle.requireAllowed("alice")).doesNotThrowAnyException();
        assertThat(throttle.remainingAttempts("alice")).isEqualTo(1);
    }

    @Test
    @DisplayName("达到阈值后抛 429，且给出剩余秒数")
    void locksAfterThreshold() {
        TestClock clock = new TestClock(NOW);
        LoginThrottle throttle = throttle(clock, 3, Duration.ofMinutes(10));
        for (int i = 0; i < 3; i++) {
            throttle.recordFailure("alice");
        }
        assertThatThrownBy(() -> throttle.requireAllowed("alice"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("600 秒")
                .satisfies(ex -> assertThat(((BizException) ex).getStatus())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(throttle.remainingAttempts("alice")).isZero();
    }

    @Test
    @DisplayName("锁定期过后可以再试，且计数清零")
    void unlocksAfterDuration() {
        TestClock clock = new TestClock(NOW);
        LoginThrottle throttle = throttle(clock, 2, Duration.ofMinutes(10));
        throttle.recordFailure("alice");
        throttle.recordFailure("alice");
        clock.advance(Duration.ofMinutes(11));
        assertThatCode(() -> throttle.requireAllowed("alice")).doesNotThrowAnyException();
        assertThat(throttle.remainingAttempts("alice")).isEqualTo(2);
    }

    @Test
    @DisplayName("登录成功清零，避免偶发输错累计到阈值")
    void successResetsCounter() {
        TestClock clock = new TestClock(NOW);
        LoginThrottle throttle = throttle(clock, 3, Duration.ofMinutes(10));
        throttle.recordFailure("alice");
        throttle.recordFailure("alice");
        throttle.recordSuccess("alice");
        assertThat(throttle.remainingAttempts("alice")).isEqualTo(3);
        assertThatCode(() -> throttle.requireAllowed("alice")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("锁定期内反复尝试不会延长锁定（否则攻击者可无限续锁）")
    void lockIsNotExtendedByRetries() {
        TestClock clock = new TestClock(NOW);
        LoginThrottle throttle = throttle(clock, 1, Duration.ofMinutes(10));
        throttle.recordFailure("alice");

        // 真实调用顺序是 requireAllowed 先拦、拦住了就不会走到 recordFailure
        clock.advance(Duration.ofMinutes(5));
        assertThatThrownBy(() -> attempt(throttle, "alice")).isInstanceOf(BizException.class);
        clock.advance(Duration.ofMinutes(4));
        assertThatThrownBy(() -> attempt(throttle, "alice")).isInstanceOf(BizException.class);

        // 到原定解锁时间就放行，说明中途的两次尝试没有把锁定推后
        clock.advance(Duration.ofMinutes(2));
        assertThatCode(() -> throttle.requireAllowed("alice")).doesNotThrowAnyException();
    }

    /** 模拟真实登录路径：先过闸门，放行才记失败。 */
    private static void attempt(LoginThrottle throttle, String username) {
        throttle.requireAllowed(username);
        throttle.recordFailure(username);
    }

    @Test
    @DisplayName("账号名大小写与空白不绕过限流")
    void keyIsNormalized() {
        TestClock clock = new TestClock(NOW);
        LoginThrottle throttle = throttle(clock, 1, Duration.ofMinutes(10));
        throttle.recordFailure("Alice");
        assertThatThrownBy(() -> throttle.requireAllowed(" alice "))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("sweep 清掉过期记录，不留下常驻内存")
    void sweepRemovesExpired() {
        TestClock clock = new TestClock(NOW);
        LoginThrottle throttle = throttle(clock, 5, Duration.ofMinutes(10));
        throttle.recordFailure("a");
        throttle.recordFailure("b");
        // 窗口是锁定期两倍；推进到窗口之外再清
        clock.advance(Duration.ofMinutes(21));
        throttle.sweep(clock.instant());
        assertThat(throttle.remainingAttempts("a")).isEqualTo(5);
    }

    private static LoginThrottle throttle(TestClock clock, int maxAttempts, Duration lock) {
        HealthOsProperties properties = new HealthOsProperties();
        properties.getAuth().setMaxLoginAttempts(maxAttempts);
        properties.getAuth().setLockDuration(lock);
        return new LoginThrottle(properties, clock);
    }

    /** 可推进的时钟：锁定与窗口都是时间相关的，用真实时钟测会变慢且不稳定。 */
    private static final class TestClock extends Clock {
        private Instant now;

        private TestClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
