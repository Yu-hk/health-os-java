package com.kangheng.healthos.service.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;

import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.model.auth.Session;

/**
 * HMAC-SHA256 签名的无状态会话令牌。
 *
 * <p>结构沿用原项目 {@code auth.py} 的 {@code body.signature}：载荷为 Base64URL(JSON)，
 * 签名为 Base64URL(HMAC(body))，比较使用常量时间实现。载荷包含：
 *
 * <pre>{@code {"v":2,"sub":"alice","jti":"...","iat":1762300000,"exp":1762328800}}</pre>
 *
 * <p><b>为什么要有 {@code jti}</b>：无状态令牌天然无法撤销——登出只清 Cookie 时，
 * 被抄走的令牌还能用到 {@code exp}（默认 8 小时）。有了唯一标识，服务端记一份吊销名单
 * 就能做到"登出即失效"，代价远小于把整份会话搬进数据库。
 *
 * <p><b>为什么不放 {@code roles}</b>：角色固化在令牌里，撤销权限就得等令牌过期。
 * 令牌只回答"你是谁"，权限每次请求现算（见 {@code UserPrincipal}）。
 *
 * <p><b>为什么旧令牌一律拒绝</b>：旧载荷没有 {@code jti}，无法吊销，放行等于留一个
 * "登出也杀不掉"的口子。升级后需重新登录一次，这个代价是明示的。
 */
public final class SessionToken {

    /** 载荷版本。结构变更就加一，旧版本自然失效，不需要维护兼容分支。 */
    private static final int VERSION = 2;

    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();

    private SessionToken() {
    }

    /** 签发新令牌；{@code jti} 随机生成。 */
    public static String create(String subject, Duration ttl, String secret, Instant now) {
        return create(subject, newId(), ttl, secret, now);
    }

    /**
     * 签发令牌，同时返回解析后的会话。
     *
     * <p>两个产物必须由同一次生成给出：如果调用方各自生成 {@code jti}，
     * 令牌里的和响应体里的就会不一致，登出时吊销的是另一个标识，表现为"登出没生效"。
     */
    public static Issued issue(String subject, Duration ttl, String secret, Instant now) {
        String id = newId();
        long issued = now.getEpochSecond();
        long expires = now.plus(ttl).getEpochSecond();
        Session session = new Session(subject, id, Instant.ofEpochSecond(issued),
                Instant.ofEpochSecond(expires));
        return new Issued(create(subject, id, ttl, secret, now), session);
    }

    /** @param token 待写入 Cookie 的令牌；@param session 同一令牌解析出的会话 */
    public record Issued(String token, Session session) {
    }

    /**
     * 用指定 {@code jti} 签发。
     *
     * <p>只在"续期"时用：续期要保留原 {@code jti}，否则吊销名单里的旧记录对新令牌无效，
     * 用户登出后刚续出来的令牌又能用——这类漏洞不会报错，只会静默存在。
     */
    public static String create(String subject, String id, Duration ttl, String secret, Instant now) {
        long issued = now.getEpochSecond();
        long expires = now.plus(ttl).getEpochSecond();
        String payload = "{\"v\":" + VERSION
                + ",\"sub\":" + Json.write(subject)
                + ",\"jti\":" + Json.write(id)
                + ",\"iat\":" + issued
                + ",\"exp\":" + expires + "}";
        String body = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + ENCODER.encodeToString(sign(body, secret));
    }

    /**
     * 校验签名与有效期，通过则返回会话。
     *
     * <p>过期、篡改、格式非法一律返回 empty 而不是抛异常——调用方只需要"有没有身份"这一个判断，
     * 区分失败原因反而容易在高频路径上写出不一致的响应。
     */
    public static Optional<Session> read(String token, String secret, Instant now) {
        if (token == null || token.isBlank() || secret == null || secret.isEmpty()) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return Optional.empty();
        }
        String body = token.substring(0, dot);
        byte[] expected = sign(body, secret);
        byte[] actual;
        try {
            actual = DECODER.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            return Optional.empty();
        }
        try {
            JsonNode node = Json.mapper().readTree(DECODER.decode(body));
            if (node.path("v").asInt(0) != VERSION) {
                return Optional.empty();
            }
            String subject = node.path("sub").asText("");
            String id = node.path("jti").asText("");
            long issued = node.path("iat").asLong(0);
            long expires = node.path("exp").asLong(0);
            if (subject.isBlank() || id.isBlank() || expires <= 0) {
                return Optional.empty();
            }
            Session session = new Session(subject, id,
                    issued > 0 ? Instant.ofEpochSecond(issued) : Instant.EPOCH,
                    Instant.ofEpochSecond(expires));
            return session.isExpiredAt(now) ? Optional.empty() : Optional.of(session);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private static String newId() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        // 用随机字节而不是 UUID 字符串：同样的信息量，短 1/3，Cookie 更小
        return ENCODER.encodeToString(bytes);
    }

    private static byte[] sign(String body, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(body.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ex) {
            throw new IllegalStateException("会话签名计算失败", ex);
        }
    }
}
