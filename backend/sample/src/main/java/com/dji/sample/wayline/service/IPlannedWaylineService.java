package com.dji.sample.wayline.service;

import com.dji.sample.wayline.model.dto.PlannedWaylineDTO;
import com.dji.sample.wayline.model.param.CreatePlannedWaylineParam;
import com.dji.sample.wayline.model.param.UpdatePlannedWaylineParam;
import com.dji.sdk.common.PaginationData;

import java.util.Optional;

public interface IPlannedWaylineService {

    PaginationData<PlannedWaylineDTO> getByWorkspace(String workspaceId, long page, long pageSize);

    PlannedWaylineDTO create(String workspaceId, String username, CreatePlannedWaylineParam param);

    PlannedWaylineDTO update(String workspaceId, String id, UpdatePlannedWaylineParam param);

    void delete(String workspaceId, String id);

    Optional<PlannedWaylineDTO> getOne(String workspaceId, String id);
}
