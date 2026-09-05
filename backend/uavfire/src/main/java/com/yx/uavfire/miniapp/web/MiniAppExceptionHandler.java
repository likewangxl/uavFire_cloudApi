package com.yx.uavfire.miniapp.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Slf4j
@RestControllerAdvice(basePackages = "com.yx.uavfire.miniapp.controller")
public class MiniAppExceptionHandler {

    @ExceptionHandler(MiniAppException.class)
    public ResponseEntity<MiniAppResponse<Void>> handleMiniAppException(
            MiniAppException exception, HttpServletRequest request, HttpServletResponse response) {
        String requestId = MiniAppRequestIds.apply(request, response);
        return ResponseEntity.status(exception.getStatus())
                .body(MiniAppResponse.error(requestId, exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<MiniAppResponse<Void>> handleUnreadableBody(
            HttpMessageNotReadableException exception, HttpServletRequest request, HttpServletResponse response) {
        String requestId = MiniAppRequestIds.apply(request, response);
        return ResponseEntity.badRequest().body(MiniAppResponse.error(
                requestId, MiniAppErrorCode.VALIDATION_ERROR, "请求体不是有效的 JSON"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<MiniAppResponse<Void>> handleUnexpected(
            Exception exception, HttpServletRequest request, HttpServletResponse response) {
        String requestId = MiniAppRequestIds.apply(request, response);
        log.error("Mini program request failed. requestId={}", requestId, exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(MiniAppResponse.error(
                requestId, MiniAppErrorCode.INTERNAL_ERROR, "服务暂时不可用"));
    }
}
