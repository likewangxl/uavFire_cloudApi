package com.yx.uavfire.miniapp.web;

import org.springframework.http.HttpStatus;

public class MiniAppException extends RuntimeException {

    private final HttpStatus status;
    private final MiniAppErrorCode code;

    public MiniAppException(HttpStatus status, MiniAppErrorCode code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public MiniAppErrorCode getCode() {
        return code;
    }
}
