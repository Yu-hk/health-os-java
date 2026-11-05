package com.kangheng.healthos.service.auth;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.mapper.auth.AuthLoginAttemptMapper;
import com.kangheng.healthos.mapper.auth.AuthSessionRevocationMapper;
import com.kangheng.healthos.model.auth.AuthLoginAttemptRow;
import com.kangheng.healthos.model.auth.AuthSessionRevocation;
import com.kangheng.healthos.model.auth.Session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 落库模式的认证状态：吊销名单与登录失败计数。
 *
 * <p>这组用例盯的是<b>内存实现根本做不到的两件事</b>，而不是把同一套逻辑换个后端再测一遍：
 * <ol>
 *   <li>「本地内存是空的、库里有一条记录」时仍要判成已吊销——这正是重启后、
 *       以及另一个节点收到同一张 Cookie 时的真实状态；</li>
 *   <li>库不可用时要降级到本地并<b>留下痕迹</b>，不能静默放行。</li>
 * </ol>
 */
class AuthSessionStoreJdbcTest {

    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");

    private AuthSessionRevocationMapper revocationMapper;
    private AuthLoginAttemptMapper attemptMapper;
    private HealthOsProperties properties;

    @BeforeEach
    void setUp() {
        revocationMapper = mock(AuthSessionRevocationMapper.class);
        attemptMapper = mock(AuthLoginAttemptMapper.class);
        properties = new HealthOsProperties();
        properties.getAuth().setSessionStore("jdbc");
        properties.getAuth().setMaxLoginAttempts(3);
        properties.getAuth().setLockDuration(Duration.ofMinutes(15));
    }

    // ------------------------------------------------------------ 吊销名单

    @Test
    @DisplayName("本地没有、库里有：判为已吊销（重启后 / 换节点的真实场景）")
    void revokedInDatabaseEvenWhenLocalCacheIsEmpty() {
        SessionRevocationRegistry registry = new SessionRevocationRegistry(revocationMapper, properties);
        when(revocationMapper.selectById("jti-1")).thenReturn(row("jti-1", NOW.plus(Duration.ofHours(1))));

        // 这个 registry 是新建的，本地 Map 一定是空的：判定只能来自库
        assertThat(registry.size()).isZero();
        assertThat(registry.isRevoked("jti-1", NOW)).isTrue();
    }

    @Test
    @DisplayName("吊销会写库，重复吊销不报错（幂等）")
    void revokeWritesToDatabase() {
        SessionRevocationRegistry registry = new SessionRevocationRegistry(revocationMapper, properties);
        registry.revoke(session("jti-1", NOW.plus(Duration.ofHours(1))));
        verify(revocationMapper).insert(any(AuthSessionRevocation.class));

        // 第二次（前端重试/用户连点）不得抛出
        registry.revoke(session("jti-1", NOW.plus(Duration.ofHours(1))));
        assertThat(registry.degraded()).isFalse();
    }

    @Test
    @DisplayName("库里的记录已过期 → 不算吊销")
    void expiredRowIsNotRevoked() {
        SessionRevocationRegistry registry = new SessionRevocationRegistry(revocationMapper, properties);
        when(revocationMapper.selectById("jti-1")).thenReturn(row("jti-1", NOW.minusSeconds(1)));
        assertThat(registry.isRevoked("jti-1", NOW)).isFalse();
    }

    @Test
    @DisplayName("库读写抛异常 → 退回本地名单，且 degraded 置位（降级要留痕）")
    void degradesToLocalWhenDatabaseFails() {
        SessionRevocationRegistry registry = new SessionRevocationRegistry(revocationMapper, properties);
        // 吊销时库就写失败
        when(revocationMapper.insert(any(AuthSessionRevocation.class)))
                .thenThrow(new IllegalStateException("connection refused"));
        registry.revoke(session("jti-1", NOW.plus(Duration.ofHours(1))));

        assertThat(registry.degraded()).isTrue();
        // 本地那份仍然拦得住——应急可用，但不是常态
        when(revocationMapper.selectById(anyString()))
                .thenThrow(new IllegalStateException("connection refused"));
        assertThat(registry.isRevoked("jti-1", NOW)).isTrue();
        assertThat(registry.shared()).isTrue();
    }

    @Test
    @DisplayName("session-store=memory：即使注入了 mapper 也不查库")
    void memoryModeNeverTouchesDatabase() {
        properties.getAuth().setSessionStore("memory");
        SessionRevocationRegistry registry = new SessionRevocationRegistry(revocationMapper, properties);
        registry.revoke(session("jti-1", NOW.plus(Duration.ofHours(1))));
        assertThat(registry.isRevoked("jti-1", NOW)).isTrue();
        assertThat(registry.shared()).isFalse();
        verify(revocationMapper, never()).insert(any(AuthSessionRevocation.class));
        verify(revocationMapper, never()).selectById(anyString());
    }

    // ------------------------------------------------------------ 登录失败计数

    @Test
    @DisplayName("记录失败时按「窗口 = 锁定 × 2」传参给库，口径与内存实现一致")
    void recordFailurePassesWindowAndLockToDatabase() {
        LoginThrottle throttle = new LoginThrottle(properties, fixedClock(), attemptMapper);
        throttle.recordFailure("Admin");

        // 用户名必须 normalize 成小写，否则 Admin 与 admin 会各占一行、阈值翻倍
        verify(attemptMapper).recordFailure(eq("admin"), any(OffsetDateTime.class),
                eq(Duration.ofMinutes(30).getSeconds()), eq(3),
                eq(Duration.ofMinutes(15).getSeconds()));
    }

    @Test
    @DisplayName("库里有锁定记录 → 直接 429，不再走口令校验")
    void requireAllowedRejectsWhenLockedInDatabase() {
        LoginThrottle throttle = new LoginThrottle(properties, fixedClock(), attemptMapper);
        when(attemptMapper.find("admin")).thenReturn(locked("admin", NOW.plusSeconds(600)));

        assertThatThrownBy(() -> throttle.requireAllowed("admin"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("秒后再试")
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo("TOO_MANY_REQUESTS");
    }

    @Test
    @DisplayName("锁定已到期 → 放行并删掉那条记录")
    void expiredLockIsCleared() {
        LoginThrottle throttle = new LoginThrottle(properties, fixedClock(), attemptMapper);
        when(attemptMapper.find("admin")).thenReturn(locked("admin", NOW.minusSeconds(1)));

        throttle.requireAllowed("admin");
        verify(attemptMapper).deleteById("admin");
    }

    @Test
    @DisplayName("登录成功必须清库，否则偶发输错会一直累计到阈值")
    void recordSuccessClearsDatabase() {
        LoginThrottle throttle = new LoginThrottle(properties, fixedClock(), attemptMapper);
        throttle.recordSuccess("admin");
        verify(attemptMapper).deleteById("admin");
    }

    @Test
    @DisplayName("库不可用时限流降级为本地计数，并置位 degraded")
    void throttleDegradesWhenDatabaseFails() {
        LoginThrottle throttle = new LoginThrottle(properties, fixedClock(), attemptMapper);
        when(attemptMapper.find(anyString())).thenThrow(new IllegalStateException("db down"));
        when(attemptMapper.recordFailure(anyString(), any(), anyLong(), anyInt(), anyLong()))
                .thenThrow(new IllegalStateException("db down"));

        throttle.recordFailure("admin");
        assertThat(throttle.degraded()).isTrue();

        // 本地计数仍然生效：连续失败到阈值后应被锁
        throttle.recordFailure("admin");
        throttle.recordFailure("admin");
        assertThatThrownBy(() -> throttle.requireAllowed("admin"))
                .isInstanceOf(BizException.class);
    }

    // ------------------------------------------------------------ 夹具

    private static java.time.Clock fixedClock() {
        return java.time.Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static Session session(String id, Instant expiresAt) {
        return new Session("alice", id, NOW, expiresAt);
    }

    private static AuthSessionRevocation row(String id, Instant expiresAt) {
        AuthSessionRevocation row = new AuthSessionRevocation();
        row.setSessionId(id);
        row.setSubject("alice");
        row.setExpiresAt(OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        row.setRevokedAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return row;
    }

    private static AuthLoginAttemptRow locked(String key, Instant lockedUntil) {
        AuthLoginAttemptRow row = new AuthLoginAttemptRow();
        row.setUsernameKey(key);
        row.setFailCount(3);
        row.setWindowStart(OffsetDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC));
        row.setLockedUntil(OffsetDateTime.ofInstant(lockedUntil, ZoneOffset.UTC));
        row.setUpdatedAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return row;
    }
}
