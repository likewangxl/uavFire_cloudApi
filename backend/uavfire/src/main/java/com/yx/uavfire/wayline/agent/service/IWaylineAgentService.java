package com.yx.uavfire.wayline.agent.service;

import com.yx.uavfire.wayline.agent.model.WaylineAgentKmzEntry;
import com.yx.uavfire.wayline.agent.model.dto.WaylineAgentCommandAckDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineAgentCommandDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineControlDataDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineDispatchDataDTO;

import java.util.Optional;

public interface IWaylineAgentService {

    WaylineAgentCommandDTO pollCommand(String droneSn);

    void acknowledgeCommand(String droneSn, WaylineAgentCommandAckDTO ack);

    WaylineAgentCommandDTO dispatchWayline(String droneSn, WaylineDispatchDataDTO data);

    WaylineAgentCommandDTO pauseMission(String droneSn, WaylineControlDataDTO data);

    WaylineAgentCommandDTO resumeMission(String droneSn, WaylineControlDataDTO data);

    WaylineAgentCommandDTO stopMission(String droneSn, WaylineControlDataDTO data);

    WaylineAgentCommandDTO queryBreakpoint(String droneSn, WaylineControlDataDTO data);

    void prepareKmz(String droneSn, String missionId, byte[] kmzBytes);

    Optional<WaylineAgentKmzEntry> getKmz(String droneSn, String missionId);
}
