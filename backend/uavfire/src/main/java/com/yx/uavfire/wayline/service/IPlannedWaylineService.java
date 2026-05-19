package com.yx.uavfire.wayline.service;

import com.yx.uavfire.wayline.model.dto.PlannedWaylineDTO;
import com.yx.uavfire.wayline.model.param.CreatePlannedWaylineParam;
import com.yx.uavfire.wayline.model.param.PreparePlannedWaylineTaskParam;
import com.yx.uavfire.wayline.model.param.PublishPlannedWaylineResponse;
import com.yx.uavfire.wayline.model.param.UpdatePlannedWaylineParam;
import com.dji.sdk.common.PaginationData;

import java.util.Optional;

public interface IPlannedWaylineService {

    PaginationData<PlannedWaylineDTO> getByWorkspace(String workspaceId, long page, long pageSize);

    PlannedWaylineDTO create(String workspaceId, String username, CreatePlannedWaylineParam param);

    PlannedWaylineDTO update(String workspaceId, String id, UpdatePlannedWaylineParam param);

    PublishPlannedWaylineResponse publish(String workspaceId, String id, String username);

    PlannedWaylineDTO generateFile(String workspaceId, String id, String username);

    PlannedWaylineDTO prepareTask(String workspaceId, String id, String username, PreparePlannedWaylineTaskParam param);

    PlannedWaylineDTO executeTask(String workspaceId, String id);

    PlannedWaylineDTO cancelTask(String workspaceId, String id);

    // P2: 实时控制 (按 entity.dockSn 路由到 dock Cloud SDK 或 agent path)
    PlannedWaylineDTO pauseTask(String workspaceId, String id);

    PlannedWaylineDTO recoveryTask(String workspaceId, String id);

    PlannedWaylineDTO stopTask(String workspaceId, String id);

    PlannedWaylineDTO queryBreakpoint(String workspaceId, String id);

    void delete(String workspaceId, String id);

    Optional<PlannedWaylineDTO> getOne(String workspaceId, String id);
}
