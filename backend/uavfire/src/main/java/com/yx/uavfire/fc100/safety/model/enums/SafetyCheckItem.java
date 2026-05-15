package com.yx.uavfire.fc100.safety.model.enums;

/**
 * spec §5.2 — 13 个校验项。MVP 11 项真实现 + 2 项 PLACEHOLDER。
 */
public enum SafetyCheckItem {
    FIRE_CONFIDENCE,
    FIRE_COORDINATE_VALID,
    TAKEOFF_POINT_VALID,
    WIND_SPEED,
    PAYLOAD_WEIGHT,
    AIRCRAFT_ONLINE,
    BATTERY_LEVEL,
    RTK_STATUS,
    DISTANCE_LIMIT,
    ALTITUDE_LIMIT,
    MANUAL_CONFIRMATION,
    /** MVP placeholder — 二期接 DJI 禁飞区 API */
    NO_FLY_ZONE,
    /** MVP placeholder */
    PEOPLE_RISK_AREA
}
