package com.kangheng.healthos.model.auth;

import java.time.Instant;
import java.util.List;

/**
 * 本次请求的调用者：身份 + 角色 + 会话到期时间。
 *
 * <p><b>角色不写进令牌</b>，每次请求按当前配置重新算。理由是"撤销权限"必须立即生效：
 * 把一个账号从运维名单里移走，如果角色固化在 8 小时有效期的令牌里，那这 8 小时它仍是管理员。
 * 令牌只证明"你是谁"，"你能干什么"由服务端当下判定。
 *
 * @param username  登录名
 * @param roles     本次请求生效的角色（有序，权限由高到低）
 * @param expiresAt 当前会话到期时间，供 {@code /auth/me} 与前端提前续期使用
 */
public record UserPrincipal(String username, List<String> roles, Instant expiresAt) {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_TRACE_READER = "TRACE_READER";
    /** 模型配置中心管理权限。与运维权限分开——改路由是全系统生效的动作。 */
    public static final String ROLE_MODEL_ADMIN = "MODEL_ADMIN";
    public static final String ROLE_USER = "USER";

    public UserPrincipal {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public boolean hasRole(String role) {
        return role != null && roles.contains(role);
    }

    /** 运维口径与 {@code AccessControl.isAdmin} 同源，不另立判断。 */
    public boolean isAdmin() {
        return hasRole(ROLE_ADMIN);
    }

    @Override
    public String toString() {
        return username + roles;
    }
}
