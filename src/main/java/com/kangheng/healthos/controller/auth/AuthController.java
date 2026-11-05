package com.kangheng.healthos.controller.auth;

import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import com.kangheng.healthos.common.AccessControl;
import com.kangheng.healthos.model.auth.Session;
import com.kangheng.healthos.model.auth.UserPrincipal;
import com.kangheng.healthos.service.auth.AuthService;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口，路径与语义对齐原项目 {@code /api/v2/auth/*}。
 *
 * <p>三个端点的职责：
 * <ul>
 *   <li>{@code /login} 校验口令并下发会话 Cookie；</li>
 *   <li>{@code /logout} <b>真正吊销令牌</b>——不只是让浏览器删 Cookie；</li>
 *   <li>{@code /me} 返回当前身份、角色与到期时间，角色由服务端现算而不是写死。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AccessControl accessControl;

    public AuthController(AuthService authService, AccessControl accessControl) {
        this.authService = authService;
        this.accessControl = accessControl;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest servletRequest,
                                               HttpServletResponse response) {
        AuthService.IssuedSession issued = authService.login(
                request.username(), request.password(), clientIp(servletRequest));
        Session session = issued.session();
        response.setHeader(HttpHeaders.SET_COOKIE, SessionCookie.header(
                authService.cookieName(), issued.token(),
                authService.ttl().toSeconds(), authService.cookieSecure()));
        return ResponseEntity.ok(new LoginResponse(
                session.subject(),
                accessControl.rolesOf(session.subject()),
                session.expiresAt(),
                authService.ttl().toSeconds()));
    }

    /**
     * 登出。
     *
     * <p>先吊销令牌再清 Cookie：只清 Cookie 的话，被抄走的令牌还能一直用到过期——
     * 这是"看起来登出了"和"真的登出了"的区别。
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(CurrentUserArgumentResolver.readCookie(request, authService.cookieName()));
        response.setHeader(HttpHeaders.SET_COOKIE, SessionCookie.cleared(
                authService.cookieName(), authService.cookieSecure()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeResponse me(@CurrentUser UserPrincipal principal) {
        return new MeResponse(principal.username(), principal.roles(), principal.expiresAt());
    }

    /** 取真实来源 IP 用于审计；反向代理下取 X-Forwarded-For 首段。 */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String username, List<String> roles, Instant expiresAt,
                                long expiresInSeconds) {
    }

    public record MeResponse(String username, List<String> roles, Instant expiresAt) {
    }
}
