package com.kangheng.healthos.service.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import com.kangheng.healthos.model.auth.Session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionTokenTest {

    private static final String SECRET = "unit-test-secret";
    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");

    @Test
    @DisplayName("正常签发的令牌可以还原身份、jti 与到期时间")
    void roundTrip() {
        String token = SessionToken.create("admin", Duration.ofHours(1), SECRET, NOW);
        Optional<Session> session = SessionToken.read(token, SECRET, NOW);
        assertThat(session).isPresent();
        assertThat(session.get().subject()).isEqualTo("admin");
        assertThat(session.get().id()).isNotBlank();
        assertThat(session.get().expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    @DisplayName("issue 产出的令牌与响应体里的 jti 必须一致")
    void issueKeepsTokenAndSessionInSync() {
        SessionToken.Issued issued = SessionToken.issue("alice", Duration.ofHours(1), SECRET, NOW);
        Session parsed = SessionToken.read(issued.token(), SECRET, NOW).orElseThrow();
        assertThat(parsed.id()).isEqualTo(issued.session().id());
        assertThat(parsed.subject()).isEqualTo(issued.session().subject());
    }

    @Test
    @DisplayName("每次签发的 jti 都不同，否则吊销一个会连带吊销别人的会话")
    void jtiIsUnique() {
        Session a = SessionToken.read(
                SessionToken.create("u", Duration.ofHours(1), SECRET, NOW), SECRET, NOW).orElseThrow();
        Session b = SessionToken.read(
                SessionToken.create("u", Duration.ofHours(1), SECRET, NOW), SECRET, NOW).orElseThrow();
        assertThat(a.id()).isNotEqualTo(b.id());
    }

    @Test
    @DisplayName("换密钥后签名不再有效")
    void rejectsWrongSecret() {
        String token = SessionToken.create("admin", Duration.ofHours(1), SECRET, NOW);
        assertThat(SessionToken.read(token, "another-secret", NOW)).isEmpty();
    }

    @Test
    @DisplayName("过期令牌被拒绝")
    void rejectsExpired() {
        String token = SessionToken.create("admin", Duration.ofMinutes(1), SECRET, NOW);
        assertThat(SessionToken.read(token, SECRET, NOW.plus(Duration.ofMinutes(2)))).isEmpty();
    }

    @Test
    @DisplayName("篡改载荷后签名校验失败")
    void rejectsTamperedBody() {
        String token = SessionToken.create("admin", Duration.ofHours(1), SECRET, NOW);
        String forged = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"v\":2,\"sub\":\"root\",\"jti\":\"x\",\"exp\":9999999999}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(SessionToken.read(forged + token.substring(token.indexOf('.')), SECRET, NOW)).isEmpty();
    }

    @Test
    @DisplayName("旧版载荷（无 jti）一律拒绝：无法吊销的令牌不能放行")
    void rejectsLegacyPayloadWithoutJti() {
        String legacyBody = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"admin\",\"exp\":9999999999}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // 用真实签名包住旧载荷：证明拒绝的原因是没有 jti，而不是签名不对
        String token = legacyBody + "." + signAsServer(legacyBody);
        assertThat(SessionToken.read(token, SECRET, NOW)).isEmpty();
    }

    @Test
    @DisplayName("格式非法时返回空而不是抛异常")
    void handlesMalformedToken() {
        assertThat(SessionToken.read(null, SECRET, NOW)).isEmpty();
        assertThat(SessionToken.read("", SECRET, NOW)).isEmpty();
        assertThat(SessionToken.read("no-dot-here", SECRET, NOW)).isEmpty();
        assertThat(SessionToken.read(".onlysignature", SECRET, NOW)).isEmpty();
        assertThat(SessionToken.read("body.", SECRET, NOW)).isEmpty();
        assertThat(SessionToken.read("a.!!!not-base64!!!", SECRET, NOW)).isEmpty();
    }

    /** 复刻服务端签名，用于构造"签名正确但载荷不合规"的样本。 */
    private static String signAsServer(String body) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
