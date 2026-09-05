package com.yx.uavfire.wayline.agent.service;

import com.yx.uavfire.wayline.agent.model.WaylineAgentKmzEntry;
import com.yx.uavfire.wayline.agent.model.dto.WaylineAgentCommandAckDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineAgentCommandDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineControlDataDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineDispatchDataDTO;

import java.util.Optional;

public interface IWaylineAgentService {

    WaylineAgentCommandDTO pollCommand(String droneSn);

    /**
     * True only when this backend instance has recently been polled by the
     * RC-side wayline command router. Device telemetry alone is insufficient:
     * older Agent builds can report online while never consuming wayline commands.
     */
    boolean hasRecentCommandPoll(String droneSn, long maxAgeMs);

    void acknowledgeCommand(String droneSn, WaylineAgentCommandAckDTO ack);

    WaylineAgentCommandDTO dispatchWayline(String droneSn, WaylineDispatchDataDTO data);

    WaylineAgentCommandDTO pauseMission(String droneSn, WaylineControlDataDTO data);

    WaylineAgentCommandDTO resumeMission(String droneSn, WaylineControlDataDTO data);

    WaylineAgentCommandDTO stopMission(String droneSn, WaylineControlDataDTO data);

    WaylineAgentCommandDTO queryBreakpoint(String droneSn, WaylineControlDataDTO data);

    void prepareKmz(String droneSn, String missionId, byte[] kmzBytes);

    Optional<WaylineAgentKmzEntry> getKmz(String droneSn, String missionId);
}
