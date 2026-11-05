package com.kangheng.healthos.spi.routing;

/**
 * 当日用量已达配额。
 *
 * <p>带 {@code used}/{@code limit} 是为了让上层能写清"超了多少"，而不是只报一句"超限"。
 * 用 {@link RuntimeException} 而不是受检异常：调用点在中途，没法为它设计恢复路径，
 * 唯一正确的处理就是让这次调用失败并把原因说清楚。
 */
public class ModelQuotaExceededException extends RuntimeException {

    /** 触发的维度：tokens / cost。 */
    public static final String QUOTA_TOKENS = "MODEL_QUOTA_TOKENS_EXCEEDED";
    public static final String QUOTA_COST = "MODEL_QUOTA_COST_EXCEEDED";

    private final String code;
    private final long used;
    private final long limit;

    public ModelQuotaExceededException(String code, String message, long used, long limit) {
        super(message);
        this.code = code;
        this.used = used;
        this.limit = limit;
    }

    public String code() {
        return code;
    }

    public long used() {
        return used;
    }

    public long limit() {
        return limit;
    }
}
