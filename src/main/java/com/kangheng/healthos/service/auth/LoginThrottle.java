package com.kangheng.healthos.service.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.config.HealthOsProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 登录失败限流：同一账号连续失败达到阈值后锁定一段时间。
 *
 * <p><b>为什么按账号而不是按 IP</b>：按 IP 锁定的假设是"攻击者只用一个 IP"，
 * 实际换 IP 的成本极低；而按账号锁定直接保护了目标账号本身。代价是攻击者可以用
 * 别人的用户名把自己锁在门外（拒绝服务），所以阈值与锁定时长都必须短——
 * 这里取 5 次 / 15 分钟，正常用户打错几次口令不受影响，爆库速度被压到每分钟 0.33 次以内。
 *
 * <p><b>为什么记在内存</b>：与吊销名单同理，单实例部署够用且零依赖。集群化时要换共享存储，
 * 否则每个节点各算各的，实际允许次数会变成 N 倍。这一点写在 README 的差异清单里。
 *
 * <p>计数采用滑动窗口：窗口内累加，超过窗口自动归零。锁定不续期——
 * 锁定期内不再累加，避免攻击者靠持续尝试把锁定时间无限延长。
 */
@Component
public class LoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    /** 超过这个条目数就清理一轮，防止有人用随机用户名刷内存。 */
    private static final int SWEEP_THRESHOLD = 1024;

    private final int maxAttempts;
    private final Duration lockDuration;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /**
     * 供 Spring 装配的构造器。
     *
     * <p>与 {@code AuthService} 同理：并存一个收 {@link Clock} 的包内构造器时，
     * 必须显式标注，否则 Spring 会去找无参构造器然后启动失败。
     */
    @Autowired
    public LoginThrottle(HealthOsProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /**
     * 显式注入时钟的构造器，理由同 {@code AuthService}：锁定与窗口都是时间语义，
     * 用真实时钟测只能靠 {@code sleep}，既慢又不稳。
     */
    public LoginThrottle(HealthOsProperties properties, Clock clock) {
        HealthOsProperties.Auth auth = properties.getAuth();
        this.maxAttempts = Math.max(1, auth.getMaxLoginAttempts());
        this.lockDuration = auth.getLockDuration() == null
                ? Duration.ofMinutes(15) : auth.getLockDuration();
        // 窗口取锁定期的两倍：太短则"每隔窗口试 4 次"就永远锁不上
        this.window = this.lockDuration.multipliedBy(2);
        this.clock = clock;
    }

    /** 未锁定则放行，锁定中抛 429。 */
    public void requireAllowed(String username) {
        Attempt attempt = attempts.get(key(username));
        if (attempt == null || attempt.lockedUntil() == null) {
            return;
        }
        Instant now = clock.instant();
        if (now.isBefore(attempt.lockedUntil())) {
            long seconds = Math.max(1, Duration.between(now, attempt.lockedUntil()).toSeconds());
            throw new BizException("TOO_MANY_REQUESTS",
                    "登录失败次数过多，请 " + seconds + " 秒后再试", HttpStatus.TOO_MANY_REQUESTS);
        }
        // 锁定期已过，清掉；不清的话下一次失败会直接又判成锁定
        attempts.remove(key(username));
    }

    /** 记一次失败；达到阈值即进入锁定。 */
    public void recordFailure(String username) {
        Instant now = clock.instant();
        attempts.compute(key(username), (k, previous) -> {
            int count = 1;
            Instant windowStart = now;
            if (previous != null && now.isBefore(previous.windowStart().plus(window))) {
                count = previous.count() + 1;
                windowStart = previous.windowStart();
            }
            Instant lockedUntil = count >= maxAttempts ? now.plus(lockDuration) : null;
            if (lockedUntil != null) {
                log.warn("账号 {} 连续登录失败 {} 次，锁定至 {}", username, count, lockedUntil);
            }
            return new Attempt(count, windowStart, lockedUntil);
        });
        if (attempts.size() > SWEEP_THRESHOLD) {
            sweep(now);
        }
    }

    /** 登录成功后清零，避免"偶发输错累计到阈值"。 */
    public void recordSuccess(String username) {
        attempts.remove(key(username));
    }

    /** 剩余可用次数，仅供测试与排查。 */
    public int remainingAttempts(String username) {
        Attempt attempt = attempts.get(key(username));
        if (attempt == null) {
            return maxAttempts;
        }
        if (attempt.lockedUntil() != null && clock.instant().isBefore(attempt.lockedUntil())) {
            return 0;
        }
        return Math.max(0, maxAttempts - attempt.count());
    }

    public void sweep(Instant now) {
        attempts.entrySet().removeIf(entry -> {
            Attempt attempt = entry.getValue();
            if (attempt.lockedUntil() != null) {
                return !attempt.lockedUntil().isAfter(now);
            }
            return !attempt.windowStart().plus(window).isAfter(now);
        });
    }

    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * @param count       窗口内失败次数
     * @param windowStart 窗口起点
     * @param lockedUntil 锁定到期时间；未锁定为 {@code null}
     */
    private record Attempt(int count, Instant windowStart, Instant lockedUntil) {
    }
}
