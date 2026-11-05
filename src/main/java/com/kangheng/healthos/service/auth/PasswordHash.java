package com.kangheng.healthos.service.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 口令校验：支持 PBKDF2 哈希与明文两种存储形式。
 *
 * <p>配置里的口令有两种写法：
 *
 * <pre>{@code
 * password: admin123                                        # 明文，仅限本地
 * password: "{pbkdf2}210000$<saltBase64>$<hashBase64>"      # 哈希，非 local 环境必须用这种
 * }</pre>
 *
 * <p><b>为什么自己实现而不引 spring-security-crypto</b>：只需要"校验一个口令"这一件事，
 * 引入整个 security 依赖会顺带激活它的默认过滤器链和授权注解语义，反而要写更多配置去关掉。
 * JDK 自带 {@code PBKDF2WithHmacSHA256}，实现二十行，依赖为零。
 *
 * <p><b>为什么迭代次数写死 210000</b>：写死在哈希串里（{@code {pbkdf2}210000$...}）而不是
 * 代码常量，是为了将来调高次数时旧哈希仍然可校验——口令哈希一旦发布就不能假设能重算。
 *
 * <p>生成哈希：{@code java -cp target/classes com.kangheng.healthos.service.auth.PasswordHash '你的口令'}
 */
public final class PasswordHash {

    private static final String PREFIX = "{pbkdf2}";
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int DEFAULT_ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private PasswordHash() {
    }

    /** 生成可写进配置的哈希串。 */
    public static String encode(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(rawPassword, salt, DEFAULT_ITERATIONS);
        return PREFIX + DEFAULT_ITERATIONS + "$" + ENCODER.encodeToString(salt)
                + "$" + ENCODER.encodeToString(hash);
    }

    public static boolean isEncoded(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /**
     * 校验口令。
     *
     * <p>明文分支用常量时间比较，避免逐字节短路泄露前缀匹配长度。哈希分支的耗时不随输入变化
     * （PBKDF2 本身是定长的），所以不需要额外处理。
     */
    public static boolean matches(String rawPassword, String stored) {
        if (rawPassword == null || stored == null || stored.isEmpty()) {
            return false;
        }
        if (!isEncoded(stored)) {
            return MessageDigest.isEqual(
                    rawPassword.getBytes(StandardCharsets.UTF_8),
                    stored.getBytes(StandardCharsets.UTF_8));
        }
        String[] parts = stored.substring(PREFIX.length()).split("\\$");
        if (parts.length != 3) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = DECODER.decode(parts[1]);
            byte[] expected = DECODER.decode(parts[2]);
            byte[] actual = pbkdf2(rawPassword, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException ex) {
            // 哈希串格式坏了当作校验失败：不能因为配置写错就放行
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            try {
                return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (Exception ex) {
            throw new IllegalStateException("口令哈希计算失败", ex);
        }
    }

    /** 生成哈希的小工具，只在命令行用。 */
    public static void main(String[] args) {
        if (args.length != 1 || args[0].isEmpty()) {
            System.err.println("用法: PasswordHash <口令>");
            System.exit(2);
        }
        System.out.println(encode(args[0]));
    }
}
