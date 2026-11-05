package com.kangheng.healthos.controller;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.BizException.ApiError;
import com.kangheng.healthos.spi.routing.ModelQuotaExceededException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常映射。
 *
 * <p>关键约束（来自原项目 AGENTS.md「不得吞掉错误后继续提交不完整数据」）：
 * 未预期异常一律返回 500 并记录堆栈，绝不返回"成功但空结果"。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiError> handleBiz(BizException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiError.of(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::describe)
                .collect(Collectors.toList());
        return ResponseEntity.badRequest().body(
                new ApiError("VALIDATION_FAILED", "请求参数不合法", OffsetDateTime.now(), details));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiError.of("PAYLOAD_TOO_LARGE", "上传文件超过允许大小"));
    }

    /**
     * 配额超限 → 429。
     *
     * <p>不用 500：550 类"服务内部错误"会让人以为是 bug；配额是"已经到达上限"的
     * 可解释状态，429 才是调用方能理解并据此停手的那种回答。消息里带已用与上限，
     * 直说超了多少。
     */
    @ExceptionHandler(ModelQuotaExceededException.class)
    public ResponseEntity<ApiError> handleQuota(ModelQuotaExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiError.of(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("未处理的异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", "服务内部错误，请查看服务端日志"));
    }

    private String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
