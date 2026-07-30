package com.yx.uavfire.manage.service;

import com.yx.uavfire.manage.model.dto.DualStreamAgentCapabilityDTO;
import com.yx.uavfire.manage.model.dto.DualStreamAgentHeartbeatDTO;
import com.yx.uavfire.manage.model.dto.DualStreamAgentStatusDTO;
import com.yx.uavfire.manage.model.dto.DualStreamCommandAckDTO;
import com.yx.uavfire.manage.model.dto.DualStreamCommandDTO;
import com.yx.uavfire.manage.model.dto.DualStreamEventDTO;
import com.yx.uavfire.manage.model.dto.DualStreamLiveGroupDTO;
import com.yx.uavfire.manage.model.dto.VisibleRoiSnapshotDTO;

import java.util.List;
import java.util.Map;

public interface IDualStreamService {

    void acceptHeartbeat(String droneSn, DualStreamAgentHeartbeatDTO heartbeat);

    void acceptStatus(String droneSn, DualStreamAgentStatusDTO status);

    void acceptCapability(String droneSn, DualStreamAgentCapabilityDTO capability);

    void acceptEvent(String taskId, DualStreamEventDTO event);

    void startVisibleLaserLocalization(
            String eventId,
            String taskId,
            String droneSn,
            long sourceTs,
            Map<String, Double> visibleRoi);

    VisibleRoiSnapshotDTO latestVisibleRoi(String taskId, long afterSourceTs);

    void expireLocalizationSessions();

    List<DualStreamEventDTO> listEvents(String taskId);

    DualStreamCommandDTO issueCommand(String droneSn, String action);

    DualStreamCommandDTO issueCommand(String droneSn, String action, Map<String, Object> params);

    DualStreamCommandDTO pollCommand(String droneSn);

    void acknowledgeCommand(String droneSn, DualStreamCommandAckDTO ack);

    DualStreamLiveGroupDTO getGroup(String droneSn);

    DualStreamCommandDTO buildCommand(String droneSn, String action);
}
