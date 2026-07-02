package com.yx.uavfire.fc100.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResult<T> {
    private int code;
    private String message;
    private T data;

    public static <T> ApiResult<T> success(T data) {
        ApiResult<T> r = new ApiResult<>();
        r.code = 0;
        r.message = "success";
        r.data = data;
        return r;
    }

    public static <T> ApiResult<T> error(Fc100ErrorCode e, String msg) {
        ApiResult<T> r = new ApiResult<>();
        r.code = e.code();
        r.message = msg != null ? msg : e.defaultMessage();
        return r;
    }

    public static <T> ApiResult<T> error(Fc100ErrorCode e, String msg, T data) {
        ApiResult<T> r = error(e, msg);
        r.data = data;
        return r;
    }

    public static <T> ApiResult<T> error(Fc100ErrorCode e) {
        return error(e, null);
    }
}
