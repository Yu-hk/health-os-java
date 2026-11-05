package com.kangheng.healthos.spi.ai;

/**
 * 向量化失败。
 *
 * <p>{@code retryable} 用来区分"等会儿再试可能好"（连不上、5xx、超时）与
 * "再试也没用"（模型不存在、维度不符、文本超长）。检索链路据此决定是降级到词面
 * 还是直接放弃这次向量召回——两者都不该把问答打挂。
 */
public class EmbeddingException extends RuntimeException {

    private final String code;

    private final boolean retryable;

    public EmbeddingException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public EmbeddingException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
