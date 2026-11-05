package com.kangheng.healthos.controller.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.kangheng.healthos.common.AccessControl;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.model.auth.Session;
import com.kangheng.healthos.model.auth.UserPrincipal;
import com.kangheng.healthos.service.auth.AuthService;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 解析 {@link CurrentUser} 参数，并在每次请求时校验会话。
 *
 * <p>把鉴权集中在一个解析器里，避免各 Controller 各自读 Cookie 导致规则漂移。
 *
 * <p>支持两种注入类型：
 * <ul>
 *   <li>{@code String}：只要用户名（大多数 Controller 只需要它做归属判断）；</li>
 *   <li>{@link UserPrincipal}：需要角色或会话到期时间时用。</li>
 * </ul>
 *
 * <p>顺带做<b>滑动续期</b>：剩余有效期不足 TTL 的 1/3 时换发新令牌。
 * 放在解析器里而不是单独端点，是因为"用户还在用就不该被踢下线"这件事不应该依赖前端记得调刷新接口。
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    static final String REQUEST_ATTRIBUTE = "healthos.currentUser";

    private final AuthService authService;
    private final AccessControl accessControl;

    public CurrentUserArgumentResolver(AuthService authService, AccessControl accessControl) {
        this.authService = authService;
        this.accessControl = accessControl;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        if (!parameter.hasParameterAnnotation(CurrentUser.class)) {
            return false;
        }
        Class<?> type = parameter.getParameterType();
        return type == UserPrincipal.class || String.class.isAssignableFrom(type);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            throw BizException.unauthorized("缺少请求上下文");
        }
        Object cached = request.getAttribute(REQUEST_ATTRIBUTE);
        UserPrincipal principal = cached instanceof UserPrincipal found
                ? found
                : authenticate(request, webRequest.getNativeResponse(HttpServletResponse.class));
        return UserPrincipal.class.equals(parameter.getParameterType())
                ? principal
                : principal.username();
    }

    private UserPrincipal authenticate(HttpServletRequest request, HttpServletResponse response) {
        String token = readCookie(request, authService.cookieName());
        Session session = authService.resolve(token)
                .orElseThrow(() -> BizException.unauthorized("登录已失效，请重新登录"));
        // 续期后的到期时间必须回填到 principal：否则响应体里的 expiresAt 是旧值，
        // 而 Cookie 已经是新值了，前端按响应体算刷新时间会提前把自己踢下线
        Session effective = renewIfNeeded(session, response);
        UserPrincipal principal = principalOf(effective);
        request.setAttribute(REQUEST_ATTRIBUTE, principal);
        return principal;
    }

    private UserPrincipal principalOf(Session session) {
        return new UserPrincipal(session.subject(),
                accessControl.rolesOf(session.subject()), session.expiresAt());
    }

    /** 需要续期就换发令牌并回写 Cookie；返回本次请求实际生效的会话。 */
    private Session renewIfNeeded(Session session, HttpServletResponse response) {
        if (response == null || !session.shouldRenew(authService.now(), authService.ttl())) {
            return session;
        }
        String renewed = authService.renew(session);
        response.setHeader(HttpHeaders.SET_COOKIE, SessionCookie.header(
                authService.cookieName(), renewed, authService.ttl().toSeconds(), authService.cookieSecure()));
        // 从新令牌解析回来，而不是自己拼一个 Session：到期时间必须与真正下发的那张令牌一致，
        // 差一秒都会让前端算出错误的刷新时刻
        return authService.resolve(renewed).orElse(session);
    }

    static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
