package com.yx.uavfire.fc100.common.web;

import com.yx.uavfire.fc100.common.ApiResult;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * spec §5.5 — 仅处理 com.yx.uavfire.fc100 包内抛出的异常。
 */
@RestControllerAdvice(basePackages = "com.yx.uavfire.fc100")
@Slf4j
public class Fc100ExceptionHandler {

    @ExceptionHandler(Fc100BusinessException.class)
    public ResponseEntity<ApiResult<?>> handleBusiness(Fc100BusinessException ex) {
        log.warn("fc100 business error: {} - {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(httpFor(ex.getErrorCode()))
            .body(ApiResult.error(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<?>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ":" + f.getDefaultMessage())
            .reduce((a, b) -> a + "; " + b)
            .orElse("invalid param");
        return ResponseEntity.badRequest()
            .body(ApiResult.error(Fc100ErrorCode.INVALID_PARAM, msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<?>> handleAll(Exception ex) {
        log.error("fc100 unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResult.error(Fc100ErrorCode.INTERNAL_ERROR, ex.getMessage()));
    }

    private HttpStatus httpFor(Fc100ErrorCode c) {
        if (c == Fc100ErrorCode.MISSION_NOT_FOUND) return HttpStatus.NOT_FOUND;
        if (c == Fc100ErrorCode.STATUS_TRANSITION_FORBIDDEN
            || c == Fc100ErrorCode.VERSION_MISMATCH
            || c == Fc100ErrorCode.IDEMPOTENCY_REPLAY) return HttpStatus.CONFLICT;
        if (c.code() >= 1000 && c.code() < 2000) return HttpStatus.BAD_REQUEST;
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
