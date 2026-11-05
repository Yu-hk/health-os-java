package com.kangheng.healthos.controller.auth;

/**
 * 会话 Cookie 的唯一构造点。
 *
 * <p>之前有两条写 Cookie 的路径：Controller 里手拼字符串、解析器里 new {@code Cookie}。
 * 手拼那份带了 {@code SameSite=Lax}，{@code Cookie} 对象那份没有——同类安全属性存在两套写法，
 * 早晚会漏掉一条。现在统一在这里生成 {@code Set-Cookie} 头。
 *
 * <p>{@code HttpOnly} 让前端 JS 读不到令牌（XSS 下无法直接抄走）；
 * {@code SameSite=Lax} 阻断跨站 POST 携带 Cookie（CSRF 的主要形态）；
 * {@code Secure} 由配置决定——本地 http 开了等于让浏览器直接丢弃 Cookie，
 * 表现为"登录成功但下一次请求就未登录"，这个坑不值得让每个人踩一遍。
 */
public final class SessionCookie {

    private SessionCookie() {
    }

    /** 生成写入会话的 {@code Set-Cookie} 头。 */
    public static String header(String name, String value, long maxAgeSeconds, boolean secure) {
        StringBuilder builder = new StringBuilder(name.length() + 96)
                .append(name).append('=').append(value)
                .append("; Path=/; HttpOnly; SameSite=Lax; Max-Age=").append(maxAgeSeconds);
        if (secure) {
            builder.append("; Secure");
        }
        return builder.toString();
    }

    /** 生成清除 Cookie 的头（{@code Max-Age=0}）。 */
    public static String cleared(String name, boolean secure) {
        return header(name, "", 0, secure);
    }
}
