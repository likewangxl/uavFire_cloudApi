package com.yx.uavfire.fc100.waypoint.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * spec §2.5 fc100.waypoint 段。所有阈值/系数运行时可调，二期通过实机数据校准。
 */
@Data
@ConfigurationProperties(prefix = "fc100.waypoint")
public class Fc100WaypointProperties {
    private double defaultCruiseSpeed = 5.0;
    private double maxRouteLengthM = 8000.0;
    private double maxDistanceFromTakeoffM = 5000.0;
    private double shortRouteMaxDistanceM = 300.0;
    private double dropAltitudeAglM = 30.0;
    /** spec §5.1 — 水柱漂移系数；MVP 默认 0.4，二期实机调优 */
    private double driftCoefficient = 0.4;
    private double maxDropOffsetM = 30.0;
}
