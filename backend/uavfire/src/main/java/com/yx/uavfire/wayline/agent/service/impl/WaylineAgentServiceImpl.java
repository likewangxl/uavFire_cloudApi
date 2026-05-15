package com.yx.uavfire.wayline.agent.service.impl;

import com.yx.uavfire.wayline.agent.model.dto.WaylineAgentCommandAckDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineAgentCommandDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineControlDataDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineDispatchDataDTO;
import com.yx.uavfire.wayline.agent.model.enums.WaylineAgentMethodEnum;
import com.yx.uavfire.wayline.agent.service.IWaylineAgentService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WaylineAgentServiceImpl implements IWaylineAgentService {

    private final Map<String, WaylineAgentCommandDTO> pendingByDrone = new ConcurrentHashMap<>();

    @Override
    public WaylineAgentCommandDTO pollCommand(String droneSn) {
        return pendingByDrone.get(droneSn);
    }

    @Override
    public void acknowledgeCommand(String droneSn, WaylineAgentCommandAckDTO ack) {
        WaylineAgentCommandDTO pending = pendingByDrone.get(droneSn);
        if (pending != null && pending.getTid().equals(ack.getTid())) {
            pendingByDrone.remove(droneSn);
        }
    }

    @Override
    public WaylineAgentCommandDTO dispatchWayline(String droneSn, WaylineDispatchDataDTO data) {
        return enqueue(droneSn, WaylineAgentMethodEnum.WAYLINE_DISPATCH, data, data.getMissionId());
    }

    @Override
    public WaylineAgentCommandDTO pauseMission(String droneSn, WaylineControlDataDTO data) {
        return enqueue(droneSn, WaylineAgentMethodEnum.WAYLINE_PAUSE, data, data.getMissionId());
    }

    @Override
    public WaylineAgentCommandDTO resumeMission(String droneSn, WaylineControlDataDTO data) {
        return enqueue(droneSn, WaylineAgentMethodEnum.WAYLINE_RESUME, data, data.getMissionId());
    }

    @Override
    public WaylineAgentCommandDTO stopMission(String droneSn, WaylineControlDataDTO data) {
        return enqueue(droneSn, WaylineAgentMethodEnum.WAYLINE_STOP, data, data.getMissionId());
    }

    @Override
    public WaylineAgentCommandDTO queryBreakpoint(String droneSn, WaylineControlDataDTO data) {
        return enqueue(droneSn, WaylineAgentMethodEnum.WAYLINE_QUERY_BREAKPOINT, data, data.getMissionId());
    }

    private WaylineAgentCommandDTO enqueue(String droneSn, WaylineAgentMethodEnum method, Object data, String bid) {
        WaylineAgentCommandDTO cmd = new WaylineAgentCommandDTO()
                .setTid(UUID.randomUUID().toString())
                .setBid(bid)
                .setTimestamp(System.currentTimeMillis())
                .setMethod(method.getMethod())
                .setData(data);
        pendingByDrone.put(droneSn, cmd);
        return cmd;
    }
}
