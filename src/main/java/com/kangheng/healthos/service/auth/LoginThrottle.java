package com.kangheng.healthos.service.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.mapper.auth.AuthLoginAttemptMapper;
import com.kangheng.healthos.model.auth.AuthLoginAttemptRow;

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
 * <p><b>为什么计数落库</b>：留在内存有两个问题——
 * 重启后计数归零（等于给爆破开了个"重启即重置"的口子），
 * 多实例下每个节点各算各的（实际允许次数变成 N × 阈值）。
 * 落库后这两个问题一起消失，代价是每次失败多一条 upsert（只有失败才写，成功不写）。
 *
 * <p>计数采用滑动窗口：窗口内累加，超过窗口自动归零。锁定不续期——
 * 锁定期内不再累加，避免攻击者靠持续尝试把锁定时间无限延长。
 *
 * <p>内存那份保留为<b>库不可用时的降级</b>，降级会打 warn 并置位 {@link #degraded()}：
 * 降级期间阈值只在本实例生效，跨节点会偏松，这一点必须能看见。
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

    /** 本地计数：库可用时与库并行维护（降级依仗），库不可用时是唯一依仗。 */
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /** {@code null} 表示纯内存模式（{@code auth.session-store=memory} 或单元测试）。 */
    private final AuthLoginAttemptMapper mapper;

    private final AtomicBoolean degraded = new AtomicBoolean(false);

    /** 纯内存模式：单元测试与 {@code session-store=memory} 用。 */
    public LoginThrottle(HealthOsProperties properties, Clock clock) {
        this(properties, clock, null);
    }

    /**
     * 供 Spring 装配的构造器。
     *
     * <p>并存多个构造器时必须显式标注，否则 Spring 会去找无参构造器然后启动失败。
     */
    @Autowired
    public LoginThrottle(HealthOsProperties properties, AuthLoginAttemptMapper mapper) {
        this(properties, Clock.systemUTC(), mapper);
    }

    /**
     * 显式注入时钟的构造器，理由同 {@code AuthService}：锁定与窗口都是时间语义，
     * 用真实时钟测只能靠 {@code sleep}，既慢又不稳。
     *
     * @param mapper 为 {@code null} 或配置为 memory 时走纯内存模式
     */
    public LoginThrottle(HealthOsProperties properties, Clock clock, AuthLoginAttemptMapper mapper) {
        HealthOsProperties.Auth auth = properties.getAuth();
        this.maxAttempts = Math.max(1, auth.getMaxLoginAttempts());
        this.lockDuration = auth.getLockDuration() == null
                ? Duration.ofMinutes(15) : auth.getLockDuration();
        // 窗口取锁定期的两倍：太短则"每隔窗口试 4 次"就永远锁不上
        this.window = this.lockDuration.multipliedBy(2);
        this.clock = clock;
        this.mapper = "memory".equalsIgnoreCase(auth.getSessionStore()) ? null : mapper;
    }

    /** 未锁定则放行，锁定中抛 429。 */
    public void requireAllowed(String username) {
        String key = key(username);
        Instant now = clock.instant();
        if (mapper != null) {
            try {
                AuthLoginAttemptRow row = mapper.find(key);
                if (row == null || row.getLockedUntil() == null) {
                    return;
                }
                Instant lockedUntil = row.getLockedUntil().toInstant();
                if (now.isBefore(lockedUntil)) {
                    throw tooManyRequests(now, lockedUntil);
                }
                // 锁定期已过：删掉，否则下一次失败会直接又被判成锁定
                mapper.deleteById(key);
                return;
            } catch (BizException limited) {
                throw limited;
            } catch (Exception ex) {
                degrade("登录失败计数读取失败，已降级为本地计数", ex);
            }
        }
        // 本地路径（也是库异常时的兜底）
        Attempt attempt = attempts.get(key);
        if (attempt == null || attempt.lockedUntil() == null) {
            return;
        }
        if (now.isBefore(attempt.lockedUntil())) {
            throw tooManyRequests(now, attempt.lockedUntil());
        }
        attempts.remove(key);
    }

    /** 记一次失败；达到阈值即进入锁定。 */
    public void recordFailure(String username) {
        String key = key(username);
        Instant now = clock.instant();
        // 本地始终记一份：库写失败时它是唯一依仗
        Attempt local = accumulateLocally(key, now);
        if (local.lockedUntil() != null) {
            log.warn("账号 {} 连续登录失败 {} 次，锁定至 {}", username, local.count(), local.lockedUntil());
        }
        if (mapper != null) {
            try {
                mapper.recordFailure(key, offset(now), window.getSeconds(),
                        maxAttempts, lockDuration.getSeconds());
            } catch (Exception ex) {
                degrade("登录失败计数落库失败，已降级为本地计数（重启即归零）", ex);
            }
        }
        if (attempts.size() > SWEEP_THRESHOLD) {
            sweep(now);
        }
    }

    /** 登录成功后清零，避免"偶发输错累计到阈值"。 */
    public void recordSuccess(String username) {
        String key = key(username);
        attempts.remove(key);
        if (mapper != null) {
            try {
                mapper.deleteById(key);
            } catch (Exception ex) {
                degrade("登录成功后的计数清零失败（该账号可能被提前锁定）", ex);
            }
        }
    }

    /** 剩余可用次数，仅供测试与排查。 */
    public int remainingAttempts(String username) {
        String key = key(username);
        Attempt attempt = attempts.get(key);
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
        if (mapper != null) {
            try {
                mapper.deleteStale(offset(now), window.getSeconds());
            } catch (Exception ex) {
                degrade("过期登录失败记录清理失败（不影响判定，下次再清）", ex);
            }
        }
    }

    /** 是否发生过降级：健康检查与排查用。 */
    public boolean degraded() {
        return degraded.get();
    }

    /** 是否使用库存储（false = 纯内存模式）。 */
    public boolean shared() {
        return mapper != null;
    }

    /** 本地累加，逻辑与原内存实现一致；返回值用于日志。 */
    private Attempt accumulateLocally(String key, Instant now) {
        return attempts.compute(key, (k, previous) -> {
            int count = 1;
            Instant windowStart = now;
            if (previous != null && now.isBefore(previous.windowStart().plus(window))) {
                count = previous.count() + 1;
                windowStart = previous.windowStart();
            }
            Instant lockedUntil = count >= maxAttempts ? now.plus(lockDuration) : null;
            return new Attempt(count, windowStart, lockedUntil);
        });
    }

    private BizException tooManyRequests(Instant now, Instant lockedUntil) {
        long seconds = Math.max(1, Duration.between(now, lockedUntil).toSeconds());
        return new BizException("TOO_MANY_REQUESTS",
                "登录失败次数过多，请 " + seconds + " 秒后再试", HttpStatus.TOO_MANY_REQUESTS);
    }

    private void degrade(String message, Exception ex) {
        if (degraded.compareAndSet(false, true)) {
            log.warn("{}: {}", message, ex.getMessage());
        } else {
            log.debug("{}: {}", message, ex.getMessage());
        }
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
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
