package com.kangheng.healthos.service.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.model.auth.Session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 认证服务的行为测试：登录、登出、令牌解析、续期。
 *
 * <p>重点是那几条"不报错但会留洞"的语义：登出后令牌必须失效、续期必须沿用原 jti、
 * 失败响应不能泄露账号是否存在。
 */
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
    private static final String SECRET = "unit-test-secret-key-32-bytes-long!!";

    @Test
    @DisplayName("口令正确时签发令牌，且令牌可解析回同一会话")
    void loginIssuesResolvableToken() {
        AuthService service = service("admin", "admin123");
        AuthService.IssuedSession issued = service.login("admin", "admin123", "127.0.0.1");
        assertThat(issued.token()).isNotBlank();
        assertThat(service.resolve(issued.token())).isPresent()
                .get()
                .satisfies(session -> {
                    assertThat(session.subject()).isEqualTo("admin");
                    assertThat(session.id()).isEqualTo(issued.session().id());
                });
    }

    @Test
    @DisplayName("口令错误抛 401，且消息不区分账号不存在与口令不符")
    void loginFailureIsOpaque() {
        AuthService service = service("admin", "admin123");
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                        com.kangheng.healthos.common.BizException.class,
                        () -> service.login("admin", "wrong", "1.1.1.1"))
                .getMessage()).isEqualTo("用户名或口令不正确");
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                        com.kangheng.healthos.common.BizException.class,
                        () -> service.login("nobody", "wrong", "1.1.1.1"))
                .getMessage()).isEqualTo("用户名或口令不正确");
    }

    @Test
    @DisplayName("登出后令牌立即失效：这是「真的登出」与「只清 Cookie」的区别")
    void logoutRevokesToken() {
        AuthService service = service("admin", "admin123");
        String token = service.login("admin", "admin123", "1.1.1.1").token();
        assertThat(service.resolve(token)).isPresent();
        service.logout(token);
        assertThat(service.resolve(token)).isEmpty();
    }

    @Test
    @DisplayName("登出幂等：无效令牌也不报错")
    void logoutIsIdempotent() {
        AuthService service = service("admin", "admin123");
        service.logout("not-a-token");
        service.logout(null);
    }

    @Test
    @DisplayName("续期沿用原 jti：否则登出后刚续出来的令牌又能用")
    void renewKeepsSessionId() {
        MutableClock clock = new MutableClock(NOW);
        AuthService service = serviceWithClock("admin", "admin123", clock);
        Session original = service.login("admin", "admin123", "1.1.1.1").session();
        // 推进到剩余有效期不足 TTL 的 1/3，才有续期的意义
        clock.advance(Duration.ofHours(7));
        assertThat(original.shouldRenew(clock.instant(), service.ttl())).isTrue();

        String renewed = service.renew(original);
        Session parsed = service.resolve(renewed).orElseThrow();
        assertThat(parsed.id()).isEqualTo(original.id());
        assertThat(parsed.expiresAt()).isAfter(original.expiresAt());
    }

    @Test
    @DisplayName("续期后登出仍然有效：jti 没变，吊销记录能命中")
    void logoutAfterRenewStillWorks() {
        AuthService service = service("admin", "admin123");
        Session original = service.login("admin", "admin123", "1.1.1.1").session();
        String renewed = service.renew(original);
        service.logout(renewed);
        assertThat(service.resolve(renewed)).isEmpty();
    }

    @Test
    @DisplayName("多账号列表按列表校验，未列出的账号无法登录")
    void multiUserListIsAuthoritative() {
        HealthOsProperties properties = baseProperties();
        properties.getAuth().setPassword("");
        HealthOsProperties.Auth.User alice = new HealthOsProperties.Auth.User();
        alice.setUsername("alice");
        alice.setPassword(PasswordHash.encode("alice-pass"));
        HealthOsProperties.Auth.User bob = new HealthOsProperties.Auth.User();
        bob.setUsername("bob");
        bob.setPassword(PasswordHash.encode("bob-pass"));
        properties.getAuth().setUsers(java.util.List.of(alice, bob));

        AuthService service = new AuthService(properties,
                new SessionRevocationRegistry(), new LoginThrottle(properties, fixedClock()));
        assertThat(service.login("alice", "alice-pass", "1.1.1.1").token()).isNotBlank();
        assertThat(service.login("bob", "bob-pass", "1.1.1.1").token()).isNotBlank();
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                        com.kangheng.healthos.common.BizException.class,
                        () -> service.login("carol", "carol-pass", "1.1.1.1")))
                .hasMessage("用户名或口令不正确");
    }

    @Test
    @DisplayName("列表里有空口令账号时该账号不可登录（不能当成空口令放行）")
    void emptyStoredPasswordNeverMatches() {
        HealthOsProperties properties = baseProperties();
        HealthOsProperties.Auth.User blank = new HealthOsProperties.Auth.User();
        blank.setUsername("blank");
        blank.setPassword("");
        properties.getAuth().setUsers(java.util.List.of(blank));

        AuthService service = new AuthService(properties,
                new SessionRevocationRegistry(), new LoginThrottle(properties, fixedClock()));
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                        com.kangheng.healthos.common.BizException.class,
                        () -> service.login("blank", "", "1.1.1.1")))
                .hasMessage("用户名或口令不正确");
    }

    @Test
    @DisplayName("连续失败达阈值后登录被 429 拦截，口令正确也不行")
    void throttledAfterRepeatedFailures() {
        HealthOsProperties properties = baseProperties();
        properties.getAuth().setUsername("admin");
        properties.getAuth().setPassword("admin123");
        properties.getAuth().setMaxLoginAttempts(3);
        AuthService service = new AuthService(properties,
                new SessionRevocationRegistry(), new LoginThrottle(properties, fixedClock()));
        for (int i = 0; i < 3; i++) {
            org.junit.jupiter.api.Assertions.assertThrows(
                    com.kangheng.healthos.common.BizException.class,
                    () -> service.login("admin", "wrong", "1.1.1.1"));
        }
        com.kangheng.healthos.common.BizException ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.kangheng.healthos.common.BizException.class,
                () -> service.login("admin", "admin123", "1.1.1.1"));
        assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("会话续期阈值：剩余不足 TTL 的 1/3 才续")
    void renewOnlyNearExpiry() {
        Session fresh = new Session("admin", "jti", NOW, NOW.plus(Duration.ofHours(8)));
        Session stale = new Session("admin", "jti", NOW, NOW.plus(Duration.ofHours(2)));
        Duration ttl = Duration.ofHours(8);
        assertThat(fresh.shouldRenew(NOW, ttl)).isFalse();
        assertThat(stale.shouldRenew(NOW, ttl)).isTrue();
        // 剩余恰好 1/3 时不续，避免边界上每次请求都换令牌
        Session edge = new Session("admin", "jti", NOW, NOW.plus(ttl.dividedBy(3)));
        assertThat(edge.shouldRenew(NOW, ttl)).isFalse();
    }

    private static AuthService service(String username, String password) {
        HealthOsProperties properties = baseProperties();
        properties.getAuth().setUsername(username);
        properties.getAuth().setPassword(password);
        return new AuthService(properties,
                new SessionRevocationRegistry(), new LoginThrottle(properties, fixedClock()));
    }

    /** 需要推进时间的场景（续期、过期）用可变时钟，避免用 sleep 把测试拖慢。 */
    private static AuthService serviceWithClock(String username, String password, Clock clock) {
        HealthOsProperties properties = baseProperties();
        properties.getAuth().setUsername(username);
        properties.getAuth().setPassword(password);
        return new AuthService(properties,
                new SessionRevocationRegistry(), new LoginThrottle(properties, clock), clock);
    }

    private static HealthOsProperties baseProperties() {
        HealthOsProperties properties = new HealthOsProperties();
        properties.getAuth().setSessionSecret(SECRET);
        properties.getAuth().setSessionTtl(Duration.ofHours(8));
        properties.getAuth().setCookieName("healthos_java_session");
        return properties;
    }

    private static Clock fixedClock() {
        return new Clock() {
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
                return NOW;
            }
        };
    }

    /** 可推进的时钟。 */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
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
