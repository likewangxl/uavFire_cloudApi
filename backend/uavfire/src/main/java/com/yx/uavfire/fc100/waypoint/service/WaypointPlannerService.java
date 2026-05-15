package com.yx.uavfire.fc100.waypoint.service;

import com.yx.uavfire.fc100.waypoint.model.dto.MissionWaypointDTO;
import com.yx.uavfire.fc100.waypoint.model.param.WaypointGenerateParam;

import java.util.List;

public interface WaypointPlannerService {

    /** 计算 7 航点（spec §5.1 P0..P6）；不落库。 */
    List<MissionWaypointDTO> plan(WaypointGenerateParam param);

    /** 落库为下一版本号；返回插入行数 */
    int persistForMission(Long missionId, List<MissionWaypointDTO> waypoints);

    /** 查最新版本航点 */
    List<MissionWaypointDTO> listLatest(Long missionId);
}
