package com.yx.uavfire.fc100.route.builder;

import com.yx.uavfire.fc100.waypoint.model.dto.MissionWaypointDTO;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/** Builder 输入。MVP 全部字段必填 + 一些默认值在 Builder 内部回退到配置。 */
@Value
@Builder
public class WpmlBuildContext {
    String missionNo;
    String aircraftSn;          // 可空（仅用于注释）
    Integer droneEnumValue;     // 可空时取默认
    Integer droneSubEnumValue;
    Integer payloadEnumValue;
    Integer payloadSubEnumValue;
    Double globalSpeed;
    /** 起飞参考点 lat,lng,alt(椭球高) —— 模板用 */
    Double takeOffLat;
    Double takeOffLng;
    Double takeOffAlt;
    Integer takeOffSecurityHeight;
    List<MissionWaypointDTO> waypoints;
    long createTimeMs;
}
