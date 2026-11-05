package com.kangheng.healthos.common;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 业务异常。错误码语义与原项目保持一致（文档状态、任务状态用同名字符串落库）。
 */
public class BizException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public BizException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static BizException notFound(String what) {
        return new BizException("NOT_FOUND", what + " 不存在", HttpStatus.NOT_FOUND);
    }

    public static BizException badRequest(String message) {
        return new BizException("BAD_REQUEST", message, HttpStatus.BAD_REQUEST);
    }

    public static BizException conflict(String message) {
        return new BizException("CONFLICT", message, HttpStatus.CONFLICT);
    }

    public static BizException unauthorized(String message) {
        return new BizException("UNAUTHORIZED", message, HttpStatus.UNAUTHORIZED);
    }

    /** 统一错误响应体。 */
    public record ApiError(String code, String message, OffsetDateTime timestamp, List<String> details) {

        public static ApiError of(String code, String message) {
            return new ApiError(code, message, OffsetDateTime.now(), List.of());
        }
    }
}
