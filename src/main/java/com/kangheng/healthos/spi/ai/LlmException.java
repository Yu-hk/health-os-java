package com.kangheng.healthos.spi.ai;

/**
 * 模型调用异常，携带"是否可重试""是否属于体量过大"两个分类位。
 *
 * <p>这两个标志直接决定流水线的处置策略：
 * <ul>
 *   <li>{@code sizeSensitive=true} → 优先把分段二分后重跑，而不是原样重试；</li>
 *   <li>{@code retryable=true} → 按指数退避重试；</li>
 *   <li>两者皆 false → 直接失败，保留检查点供人工介入。</li>
 * </ul>
 * 对应原项目 {@code _is_timeout_error} / {@code _is_size_sensitive_transport_error}。
 */
public class LlmException extends RuntimeException {

    private final boolean retryable;
    private final boolean sizeSensitive;

    public LlmException(String message, Throwable cause, boolean retryable, boolean sizeSensitive) {
        super(message, cause);
        this.retryable = retryable;
        this.sizeSensitive = sizeSensitive;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public boolean isSizeSensitive() {
        return sizeSensitive;
    }

    public static LlmException timeout(String message, Throwable cause) {
        return new LlmException(message, cause, true, false);
    }

    /** 请求体过大 / 上游截断：重试无意义，应切小分段。 */
    public static LlmException tooLarge(String message, Throwable cause) {
        return new LlmException(message, cause, false, true);
    }

    /** 结构化输出不合规：换一次采样有机会成功。 */
    public static LlmException invalidSchema(String message, Throwable cause) {
        return new LlmException(message, cause, true, false);
    }

    public static LlmException nonRetryable(String message, Throwable cause) {
        return new LlmException(message, cause, false, false);
    }
}
