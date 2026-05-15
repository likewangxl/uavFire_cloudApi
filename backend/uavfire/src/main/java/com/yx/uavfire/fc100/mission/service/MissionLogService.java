package com.yx.uavfire.fc100.mission.service;

import com.yx.uavfire.fc100.mission.model.dto.MissionLogDTO;

import java.util.List;

public interface MissionLogService {

    /**
     * 按 create_time DESC 查询指定任务的操作日志，最多返回 limit 条。
     */
    List<MissionLogDTO> listByMissionNo(String missionNo, int limit);
}
