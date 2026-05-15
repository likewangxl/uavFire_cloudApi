package com.yx.uavfire.fc100.common;

/** 业务异常基类。全局 ExceptionHandler 拦截后转 ApiResult。 */
public class Fc100BusinessException extends RuntimeException {

    private final Fc100ErrorCode errorCode;

    public Fc100BusinessException(Fc100ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public Fc100BusinessException(Fc100ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public Fc100ErrorCode getErrorCode() {
        return errorCode;
    }
}
