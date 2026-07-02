package com.yx.uavfire.fc100.common;

/** spec §5.3 错误码 */
public enum Fc100ErrorCode {
    // 1xxx 参数
    INVALID_PARAM(1000, "invalid param"),
    INVALID_COORDINATE(1001, "invalid coordinate"),
    INVALID_WIND_PARAM(1002, "invalid wind param"),
    MISSING_DEVICE_POSITION(1003, "missing device position; drone OSD not yet available"),

    // 2xxx 业务
    MISSION_NOT_FOUND(2000, "mission not found"),
    STATUS_TRANSITION_FORBIDDEN(2001, "status transition forbidden"),
    VERSION_MISMATCH(2002, "version mismatch, please refresh"),
    IDEMPOTENCY_REPLAY(2003, "idempotency replay"),
    DUPLICATE_FROM_EVENT(2004, "duplicate mission from same fire event"),

    // 3xxx 安全
    SAFETY_CHECK_FAILED(3000, "safety check failed"),
    PAYLOAD_OVERWEIGHT(3001, "payload overweight"),
    WIND_TOO_STRONG(3002, "wind too strong"),
    AIRCRAFT_OFFLINE(3003, "aircraft offline"),
    BATTERY_LOW(3004, "battery low"),
    RTK_NOT_FIXED(3005, "rtk not fixed"),
    RELEASE_CONFIRMATION_REQUIRED(3006, "release confirmation required"),
    CONTROLLED_TEST_AUTO_DISABLED(3007, "controlled test auto release disabled"),
    RELEASE_CAPABILITY_UNCONFIRMED(3008, "release capability unconfirmed"),

    // 4xxx Delivery Sync
    DELIVERY_SYNC_NETWORK(4000, "delivery sync network error"),
    DELIVERY_SYNC_AUTH(4001, "delivery sync auth failed"),
    DELIVERY_SYNC_BUSINESS(4002, "delivery sync business error"),
    DELIVERY_SYNC_TIMEOUT(4003, "delivery sync timeout"),

    // 5xxx 系统
    INTERNAL_ERROR(5000, "internal error"),
    STORAGE_ERROR(5001, "storage error"),
    DB_ERROR(5002, "db error");

    private final int code;
    private final String defaultMessage;

    Fc100ErrorCode(int code, String msg) {
        this.code = code;
        this.defaultMessage = msg;
    }

    public int code() { return code; }
    public String defaultMessage() { return defaultMessage; }
}
