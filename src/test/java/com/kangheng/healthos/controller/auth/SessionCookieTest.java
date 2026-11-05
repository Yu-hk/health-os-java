package com.kangheng.healthos.controller.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCookieTest {

    @Test
    @DisplayName("会话 Cookie 必带 HttpOnly 与 SameSite=Lax")
    void hardenedAttributesAlwaysPresent() {
        String header = SessionCookie.header("sid", "abc", 3600, false);
        assertThat(header).startsWith("sid=abc")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .contains("Path=/")
                .contains("Max-Age=3600");
        assertThat(header).doesNotContain("Secure");
    }

    @Test
    @DisplayName("配置开启时才加 Secure：本地 http 加了会导致浏览器丢弃 Cookie")
    void secureIsConfigurable() {
        assertThat(SessionCookie.header("sid", "abc", 60, true)).contains("Secure");
        assertThat(SessionCookie.header("sid", "abc", 60, false)).doesNotContain("Secure");
    }

    @Test
    @DisplayName("清除 Cookie 用 Max-Age=0 且带同样的安全属性")
    void clearedHeader() {
        String header = SessionCookie.cleared("sid", true);
        assertThat(header).startsWith("sid=")
                .contains("Max-Age=0")
                .contains("HttpOnly")
                .contains("Secure");
    }
}
