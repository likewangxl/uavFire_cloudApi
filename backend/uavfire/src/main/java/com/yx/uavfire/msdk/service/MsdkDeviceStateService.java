package com.yx.uavfire.msdk.service;

import com.yx.uavfire.msdk.model.MsdkCommandDTO;
import com.yx.uavfire.msdk.model.MsdkCommandParam;
import com.yx.uavfire.msdk.model.MsdkDeviceStateDTO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongSupplier;

@Service
public class MsdkDeviceStateService {

    private static final long COMMAND_DISPATCH_TTL_MS = 15_000L;
    private static final long ONLINE_STATE_TTL_MS = 15_000L;
    private static final Set<String> SUPPORTED_COMMANDS = Set.of(
            "start",
            "stop",
            "start_stream",
            "stop_stream",
            "focus_visible",
            "focus_thermal",
            "takeoff",
            "land",
            "return_home",
            "cancel_return_home",
            "hover",
            "emergency_stop",
            "virtual_stick",
            "fly_to_point",
            "stop_fly_to_point",
            "gimbal_reset",
            "gimbal_rotate",
            "camera_start_photo",
            "camera_start_record",
            "camera_stop_record",
            "camera_stream_source",
            "camera_zoom",
            "night_scene",
            "navigation_light",
            "laser_fill_light"
    );

    private final LongSupplier clock;

    private final Map<String, MsdkDeviceStateDTO> latestByAircraftSn = new ConcurrentHashMap<>();

    private final Map<String, Queue<MsdkCommandDTO>> commandQueues = new ConcurrentHashMap<>();

    private final Map<String, MsdkCommandDTO> commandById = new ConcurrentHashMap<>();

    public MsdkDeviceStateService() {
        this(System::currentTimeMillis);
    }

    public MsdkDeviceStateService(LongSupplier clock) {
        this.clock = clock;
    }

    public void upsert(MsdkDeviceStateDTO state) {
        if (state == null || !StringUtils.hasText(state.getAircraftSn())) {
            return;
        }
        if (state.getUpdatedAt() == null || state.getUpdatedAt() <= 0) {
            state.setUpdatedAt(clock.getAsLong());
        }
        latestByAircraftSn.put(state.getAircraftSn(), state);
    }

    public Optional<MsdkDeviceStateDTO> get(String aircraftSn) {
        if (!StringUtils.hasText(aircraftSn)) {
            return Optional.empty();
        }
        return Optional.ofNullable(latestByAircraftSn.get(aircraftSn));
    }

    public List<MsdkDeviceStateDTO> listOnline() {
        List<MsdkDeviceStateDTO> result = new ArrayList<>();
        for (MsdkDeviceStateDTO state : latestByAircraftSn.values()) {
            if (isOnline(state) && isFresh(state)) {
                result.add(state);
            }
        }
        return result;
    }

    public MsdkCommandDTO enqueueCommand(String aircraftSn, MsdkCommandParam param) {
        String commandName = param == null ? "" : param.getCommand();
        if (!isSupportedCommand(commandName)) {
            throw new IllegalArgumentException("unsupported-msdk-command:" + commandName);
        }
        long now = clock.getAsLong();
        MsdkCommandDTO command = new MsdkCommandDTO()
                .setCommandId("msdk-" + now + "-" + UUID.randomUUID().toString().substring(0, 8))
                .setAircraftSn(aircraftSn)
                .setCommand(commandName)
                .setParams(param == null ? null : param.getParams())
                .setStatus("PENDING")
                .setCreatedAt(now)
                .setUpdatedAt(now);
        commandQueues.computeIfAbsent(aircraftSn, key -> new ConcurrentLinkedQueue<>()).add(command);
        commandById.put(command.getCommandId(), command);
        return command;
    }

    public boolean isSupportedCommand(String command) {
        return StringUtils.hasText(command) && SUPPORTED_COMMANDS.contains(command);
    }

    public Optional<MsdkCommandDTO> pollCommand(String aircraftSn) {
        Queue<MsdkCommandDTO> queue = commandQueues.get(aircraftSn);
        if (queue == null) {
            return Optional.empty();
        }
        MsdkCommandDTO command = pollNextDispatchable(queue);
        if (command != null) {
            command.setStatus("DISPATCHED");
            command.setUpdatedAt(clock.getAsLong());
        }
        return Optional.ofNullable(command);
    }

    public Optional<MsdkCommandDTO> getCommand(String commandId) {
        if (!StringUtils.hasText(commandId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(commandById.get(commandId));
    }

    public Optional<MsdkCommandDTO> acknowledgeCommand(String commandId, String status, String message) {
        MsdkCommandDTO command = commandById.get(commandId);
        if (command == null) {
            return Optional.empty();
        }
        command.setStatus(StringUtils.hasText(status) ? status : "UNKNOWN");
        command.setMessage(message);
        command.setUpdatedAt(clock.getAsLong());
        return Optional.of(command);
    }

    private MsdkCommandDTO pollNextDispatchable(Queue<MsdkCommandDTO> queue) {
        long now = clock.getAsLong();
        while (true) {
            MsdkCommandDTO command = queue.poll();
            if (command == null) {
                return null;
            }
            if (now - command.getCreatedAt() <= COMMAND_DISPATCH_TTL_MS) {
                return command;
            }
            command.setStatus("EXPIRED");
            command.setMessage("command-expired-before-dispatch");
            command.setUpdatedAt(now);
        }
    }

    private boolean isOnline(MsdkDeviceStateDTO state) {
        if (state == null || !Boolean.TRUE.equals(state.getOnline())) {
            return false;
        }
        return !"DISCONNECTED".equalsIgnoreCase(state.getConnectionState());
    }

    private boolean isFresh(MsdkDeviceStateDTO state) {
        Long updatedAt = state == null ? null : state.getUpdatedAt();
        if (updatedAt == null || updatedAt <= 0) {
            return false;
        }
        return clock.getAsLong() - updatedAt <= ONLINE_STATE_TTL_MS;
    }
}
