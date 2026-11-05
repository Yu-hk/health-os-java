package com.kangheng.healthos.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 标识生成。
 *
 * <p>与原项目 {@code ids.py} 对齐：业务主键用随机 ID（带前缀便于排查），
 * 需要幂等的派生 ID（证据、分段、检查点）用稳定哈希，保证同一输入永远得到同一 ID。
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private Ids() {
    }

    /** 形如 {@code doc_1a2b3c4d5e6f7a8b9c0d1e2f}。 */
    public static String newId(String prefix) {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return prefix + "_" + HEX.formatHex(bytes);
    }

    /** 稳定 ID：同样的 parts 永远得到同样的结果，用于可重放的派生键。 */
    public static String stableId(String prefix, String... parts) {
        StringBuilder buffer = new StringBuilder(prefix);
        for (String part : parts) {
            buffer.append('\u0000').append(part == null ? "" : part);
        }
        return prefix + "_" + sha256Hex(buffer.toString()).substring(0, 24);
    }

    public static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", ex);
        }
    }
}
