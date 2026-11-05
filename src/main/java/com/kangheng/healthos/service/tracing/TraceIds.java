package com.kangheng.healthos.service.tracing;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * W3C 风格的标识符：traceId 32 位十六进制、spanId 16 位十六进制。
 *
 * <p>刻意不用业务前缀（如 {@code pat_}）——这两个值要能直接塞进
 * {@code traceparent}，被外部采集器接受。
 */
public final class TraceIds {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TraceIds() {
    }

    public static String newTraceId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static String newSpanId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
