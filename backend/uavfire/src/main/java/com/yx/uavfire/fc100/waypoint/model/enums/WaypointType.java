package com.yx.uavfire.fc100.waypoint.model.enums;

/** spec §5.1 — P0..P6 七个航点的类型 */
public enum WaypointType {
    TAKEOFF,         // P0
    CLIMB,           // P1
    APPROACH,        // P2
    HOLD_UPWIND,     // P3
    DROP,            // P4
    EXIT,            // P5
    RETURN           // P6
}
