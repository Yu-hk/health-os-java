package com.kangheng.healthos.spi.ocr;

/**
 * OCR 调用失败。
 *
 * <p>区分 {@code retryable}：超时/5xx/连接失败可以重试；4xx（比如图片格式不支持、
 * key 无效）重试没有意义，直接失败并把原因写给用户。
 */
public class OcrException extends RuntimeException {

    private final boolean retryable;

    public OcrException(String message) {
        this(message, false, null);
    }

    public OcrException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
