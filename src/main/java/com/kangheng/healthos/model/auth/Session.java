package com.kangheng.healthos.model.auth;

import java.time.Duration;
import java.time.Instant;

/**
 * 一次已验证的会话。
 *
 * <p>由 {@code SessionToken.read} 从令牌中还原，校验签名与有效期之后才会产生——
 * 因此拿到 {@code Session} 实例就意味着"这个请求确实是这个人在有效期内的请求"，
 * 上层不需要再重复判断。
 *
 * @param subject   登录名
 * @param id        令牌唯一标识（jti）。登出时按它吊销；没有它就只能等令牌自然过期
 * @param issuedAt  签发时间
 * @param expiresAt 到期时间（不含）
 */
public record Session(String subject, String id, Instant issuedAt, Instant expiresAt) {

    public boolean isExpiredAt(Instant now) {
        return now == null || !expiresAt.isAfter(now);
    }

    /** 剩余有效期；已过期返回 {@link Duration#ZERO}，不返回负数（调用方少一个分支）。 */
    public Duration remaining(Instant now) {
        if (now == null) {
            return Duration.ZERO;
        }
        Duration left = Duration.between(now, expiresAt);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /**
     * 是否该续期。
     *
     * <p>阈值取总有效期的 1/3：太晚续（如 95%）等于每次请求都换令牌，
     * 太早续（如 90%）会让令牌寿命被无限拉长，等于没有过期时间。
     */
    public boolean shouldRenew(Instant now, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return false;
        }
        return remaining(now).compareTo(ttl.dividedBy(3)) < 0;
    }
}
