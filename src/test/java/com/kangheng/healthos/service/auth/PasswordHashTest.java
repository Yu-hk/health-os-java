package com.kangheng.healthos.service.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHashTest {

    @Test
    @DisplayName("哈希串可校验出原口令，且不接受错口令")
    void encodeThenMatch() {
        String stored = PasswordHash.encode("admin123");
        assertThat(PasswordHash.isEncoded(stored)).isTrue();
        assertThat(PasswordHash.matches("admin123", stored)).isTrue();
        assertThat(PasswordHash.matches("admin1234", stored)).isFalse();
        assertThat(PasswordHash.matches("", stored)).isFalse();
    }

    @Test
    @DisplayName("同一口令两次编码结果不同（盐随机），但都能校验通过")
    void saltIsRandom() {
        String a = PasswordHash.encode("same-password");
        String b = PasswordHash.encode("same-password");
        assertThat(a).isNotEqualTo(b);
        assertThat(PasswordHash.matches("same-password", a)).isTrue();
        assertThat(PasswordHash.matches("same-password", b)).isTrue();
    }

    @Test
    @DisplayName("明文口令仍可校验：本地配置写明文不该被静默判为错口令")
    void plaintextCompatible() {
        assertThat(PasswordHash.matches("admin123", "admin123")).isTrue();
        assertThat(PasswordHash.matches("admin124", "admin123")).isFalse();
    }

    @Test
    @DisplayName("哈希串写坏时判为失败，不能因为配置错误就放行")
    void malformedHashIsRejected() {
        assertThat(PasswordHash.matches("x", "{pbkdf2}notanumber$c2FsdA$aGFzaA")).isFalse();
        assertThat(PasswordHash.matches("x", "{pbkdf2}210000$only-two-parts")).isFalse();
        assertThat(PasswordHash.matches("x", "{pbkdf2}")).isFalse();
        assertThat(PasswordHash.matches("x", "{pbkdf2}210000$!!!$!!!")).isFalse();
    }

    @Test
    @DisplayName("空口令与空存储一律不通过")
    void emptyInputsRejected() {
        assertThat(PasswordHash.matches(null, "x")).isFalse();
        assertThat(PasswordHash.matches("x", null)).isFalse();
        assertThat(PasswordHash.matches("x", "")).isFalse();
    }
}
