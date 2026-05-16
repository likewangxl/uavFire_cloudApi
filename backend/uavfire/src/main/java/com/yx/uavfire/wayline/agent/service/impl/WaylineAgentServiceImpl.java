package com.yx.uavfire.wayline.agent.service.impl;

import com.yx.uavfire.wayline.agent.model.WaylineAgentKmzEntry;
import com.yx.uavfire.wayline.agent.model.dto.WaylineAgentCommandAckDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineAgentCommandDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineControlDataDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineDispatchDataDTO;
import com.yx.uavfire.wayline.agent.model.enums.WaylineAgentMethodEnum;
import com.yx.uavfire.wayline.agent.service.IWaylineAgentService;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WaylineAgentServiceImpl implements IWaylineAgentService {

    private final Map<String, WaylineAgentCommandDTO> pendingByDrone = new ConcurrentHashMap<>();
    private final Map<String, WaylineAgentKmzEntry> kmzByDrone = new ConcurrentHashMap<>();

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

    @Override
    public void prepareKmz(String droneSn, String missionId, byte[] kmzBytes) {
        kmzByDrone.put(droneSn, new WaylineAgentKmzEntry(missionId, kmzBytes, md5Hex(kmzBytes)));
    }

    @Override
    public Optional<WaylineAgentKmzEntry> getKmz(String droneSn, String missionId) {
        WaylineAgentKmzEntry entry = kmzByDrone.get(droneSn);
        if (entry == null || !entry.getMissionId().equals(missionId)) {
            return Optional.empty();
        }
        return Optional.of(entry);
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

    private static String md5Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(bytes);
            String hex = new BigInteger(1, digest).toString(16);
            return "0".repeat(Math.max(0, 32 - hex.length())) + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm unavailable", e);
        }
    }
}
