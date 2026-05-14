package com.yx.uavfire.manage.service;

import com.yx.uavfire.manage.model.dto.DualStreamAgentCapabilityDTO;
import com.yx.uavfire.manage.model.dto.DualStreamAgentHeartbeatDTO;
import com.yx.uavfire.manage.model.dto.DualStreamAgentStatusDTO;
import com.yx.uavfire.manage.model.dto.DualStreamCommandAckDTO;
import com.yx.uavfire.manage.model.dto.DualStreamCommandDTO;
import com.yx.uavfire.manage.model.dto.DualStreamEventDTO;
import com.yx.uavfire.manage.model.dto.DualStreamLiveGroupDTO;

import java.util.List;

public interface IDualStreamService {

    void acceptHeartbeat(String droneSn, DualStreamAgentHeartbeatDTO heartbeat);

    void acceptStatus(String droneSn, DualStreamAgentStatusDTO status);

    void acceptCapability(String droneSn, DualStreamAgentCapabilityDTO capability);

    void acceptEvent(String taskId, DualStreamEventDTO event);

    List<DualStreamEventDTO> listEvents(String taskId);

    DualStreamCommandDTO issueCommand(String droneSn, String action);

    DualStreamCommandDTO pollCommand(String droneSn);

    void acknowledgeCommand(String droneSn, DualStreamCommandAckDTO ack);

    DualStreamLiveGroupDTO getGroup(String droneSn);

    DualStreamCommandDTO buildCommand(String droneSn, String action);
}
