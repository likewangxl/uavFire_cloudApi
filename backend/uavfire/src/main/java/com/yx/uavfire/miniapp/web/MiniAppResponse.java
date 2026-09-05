package com.yx.uavfire.miniapp.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class MiniAppResponse<T> {

    private final String requestId;
    private final String code;
    private final String message;
    private final T data;
    private final Map<String, Object> details;
    private final String serverTime;

    private MiniAppResponse(String requestId, String code, String message, T data,
                            Map<String, Object> details) {
        this.requestId = requestId;
        this.code = code;
        this.message = message;
        this.data = data;
        this.details = details;
        this.serverTime = Instant.now().toString();
    }

    public static <T> MiniAppResponse<T> success(String requestId, T data) {
        return new MiniAppResponse<>(requestId, "OK", "success", data, null);
    }

    public static MiniAppResponse<Void> error(String requestId, MiniAppErrorCode code, String message) {
        return new MiniAppResponse<>(requestId, code.name(), message, null, null);
    }

    public String getRequestId() {
        return requestId;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public String getServerTime() {
        return serverTime;
    }
}
