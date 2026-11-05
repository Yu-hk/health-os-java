package com.kangheng.healthos.controller.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.kangheng.healthos.common.AccessControl;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.model.auth.UserPrincipal;
import com.kangheng.healthos.service.auth.AuthService;
import com.kangheng.healthos.service.auth.LoginThrottle;
import com.kangheng.healthos.service.auth.SessionRevocationRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.ServletWebRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 解析器是"每个请求都会过"的地方，续期、注入类型、失败语义都在这里。
 *
 * <p>用真实的 {@code AuthService} + 可控时钟，而不是 mock——这里要验的恰恰是
 * 令牌、吊销、续期三者的协同，mock 掉就只剩调用记录，什么也证明不了。
 */
class CurrentUserArgumentResolverTest {

    private static final String SECRET = "unit-test-secret-key-32-bytes-long!!";
    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
    private static final Duration TTL = Duration.ofHours(8);

    private MutableClock clock;
    private AuthService authService;
    private CurrentUserArgumentResolver resolver;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        HealthOsProperties properties = new HealthOsProperties();
        properties.getAuth().setUsername("admin");
        properties.getAuth().setPassword("admin123");
        properties.getAuth().setSessionSecret(SECRET);
        properties.getAuth().setSessionTtl(TTL);
        properties.getAuth().setCookieName("sid");
        properties.getSecurity().setAdminUsers(java.util.List.of("admin"));
        properties.getSecurity().setTraceGlobalUsers(java.util.List.of("admin"));

        // 限流不是本测试的关注点，但共用同一个可控时钟，语义才一致
        authService = new AuthService(properties, new SessionRevocationRegistry(),
                new LoginThrottle(properties, clock), clock);
        resolver = new CurrentUserArgumentResolver(authService,
                new AccessControl(properties.getSecurity().getAdminUsers(),
                        properties.getSecurity().getTraceGlobalUsers()));
    }

    @Test
    @DisplayName("有效令牌注入 UserPrincipal，角色按白名单现算")
    void injectsPrincipal() {
        String token = authService.login("admin", "admin123", "1.1.1.1").token();
        MockHttpServletResponse response = new MockHttpServletResponse();
        Object resolved = resolver.resolveArgument(principalParameter(), null, request(token, response), null);

        assertThat(resolved).isInstanceOf(UserPrincipal.class);
        UserPrincipal principal = (UserPrincipal) resolved;
        assertThat(principal.username()).isEqualTo("admin");
        assertThat(principal.roles()).contains(UserPrincipal.ROLE_ADMIN, UserPrincipal.ROLE_USER);
        // 剩余有效期还很足，不该续期
        assertThat(response.getHeader("Set-Cookie")).isNull();
    }

    @Test
    @DisplayName("String 参数注入用户名，保持既有 Controller 契约")
    void injectsUsername() {
        String token = authService.login("admin", "admin123", "1.1.1.1").token();
        Object resolved = resolver.resolveArgument(usernameParameter(), null,
                request(token, new MockHttpServletResponse()), null);
        assertThat(resolved).isEqualTo("admin");
    }

    @Test
    @DisplayName("临近过期时续期，且响应体里的 expiresAt 与下发的新令牌一致")
    void renewsAndReportsNewExpiry() {
        String token = authService.login("admin", "admin123", "1.1.1.1").token();
        // 推进到剩余不足 TTL 的 1/3
        clock.advance(Duration.ofHours(6));

        MockHttpServletResponse response = new MockHttpServletResponse();
        UserPrincipal principal = (UserPrincipal) resolver.resolveArgument(
                principalParameter(), null, request(token, response), null);

        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).isNotNull().contains("sid=").contains("HttpOnly");
        String renewed = setCookie.split(";")[0].substring("sid=".length());
        var renewedSession = authService.resolve(renewed).orElseThrow();

        // 关键：响应体报的到期时间必须就是新令牌的到期时间
        assertThat(principal.expiresAt()).isEqualTo(renewedSession.expiresAt());
        assertThat(principal.expiresAt()).isAfter(clock.instant());
        // 续期沿用 jti，否则登出后新令牌又能用
        assertThat(renewedSession.id())
                .isEqualTo(authService.resolve(token).orElseThrow().id());
    }

    @Test
    @DisplayName("同一请求多次解析只认一次，避免重复续期")
    void principalIsCachedPerRequest() {
        String token = authService.login("admin", "admin123", "1.1.1.1").token();
        clock.advance(Duration.ofHours(6));
        MockHttpServletResponse response = new MockHttpServletResponse();
        ServletWebRequest request = request(token, response);

        resolver.resolveArgument(principalParameter(), null, request, null);
        String first = response.getHeader("Set-Cookie");
        resolver.resolveArgument(usernameParameter(), null, request, null);
        assertThat(response.getHeader("Set-Cookie")).isEqualTo(first);
    }

    @Test
    @DisplayName("没有 Cookie / 令牌被吊销 → 401")
    void rejectsMissingOrRevoked() {
        MockHttpServletRequest noCookie = new MockHttpServletRequest();
        assertThatThrownBy(() -> resolver.resolveArgument(principalParameter(), null,
                new ServletWebRequest(noCookie, new MockHttpServletResponse()), null))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getStatus().value()).isEqualTo(401));

        String token = authService.login("admin", "admin123", "1.1.1.1").token();
        authService.logout(token);
        assertThatThrownBy(() -> resolver.resolveArgument(principalParameter(), null,
                request(token, new MockHttpServletResponse()), null))
                .isInstanceOf(BizException.class);
    }

    private ServletWebRequest request(String token, MockHttpServletResponse response) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (token != null) {
            request.setCookies(new jakarta.servlet.http.Cookie("sid", token));
        }
        return new ServletWebRequest(request, response);
    }

    private MethodParameter principalParameter() {
        return parameter(0);
    }

    private MethodParameter usernameParameter() {
        return parameter(1);
    }

    private MethodParameter parameter(int index) {
        try {
            return new MethodParameter(
                    Target.class.getDeclaredMethod("handle", UserPrincipal.class, String.class), index);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 只为取 MethodParameter 存在；不会被调用。 */
    private static final class Target {
        void handle(@CurrentUser UserPrincipal principal, @CurrentUser String username) {
        }
    }

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
