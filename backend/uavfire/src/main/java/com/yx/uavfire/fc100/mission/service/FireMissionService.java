package com.yx.uavfire.fc100.mission.service;

import com.yx.uavfire.fc100.mission.model.dto.FireMissionDTO;

import java.util.List;

public interface FireMissionService {

    FireMissionDTO detail(String missionNo);

    List<FireMissionDTO> list(String workspaceId, String status, int page, int size);
}
