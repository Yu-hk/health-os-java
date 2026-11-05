package com.kangheng.healthos.spi.retrieval;

/**
 * 外部知识库调用失败。
 *
 * <p>{@code retryable} 的判定依据是"重试是否可能改变结果"：
 * 超时、5xx、解析任务临时失败算可重试；参数非法、鉴权失败、数据集不存在算不可重试。
 */
public class KnowledgeBaseException extends RuntimeException {

    private final boolean retryable;

    public KnowledgeBaseException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public KnowledgeBaseException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
