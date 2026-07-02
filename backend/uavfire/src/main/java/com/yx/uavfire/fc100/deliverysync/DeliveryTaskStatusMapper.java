package com.yx.uavfire.fc100.deliverysync;

import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskStatus;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent;

import java.util.Map;
import java.util.Optional;

public final class DeliveryTaskStatusMapper {

    private static final Map<String, FireMissionEvent> STATUS_EVENTS = Map.ofEntries(
        Map.entry("CREATED", FireMissionEvent.CREATE_DELIVERY_TASK),
        Map.entry("IN_PROGRESS", FireMissionEvent.START_DELIVERY),
        Map.entry("RUNNING", FireMissionEvent.START_DELIVERY),
        Map.entry("ARRIVED", FireMissionEvent.MARK_RELEASE_PENDING),
        Map.entry("READY_TO_RELEASE", FireMissionEvent.MARK_RELEASE_PENDING),
        Map.entry("PAYLOAD_RELEASE_PENDING", FireMissionEvent.MARK_RELEASE_PENDING),
        Map.entry("RETURNING", FireMissionEvent.MARK_RETURNING),
        Map.entry("GO_HOME", FireMissionEvent.MARK_RETURNING),
        Map.entry("COMPLETED", FireMissionEvent.MARK_RETURN_COMPLETED),
        Map.entry("FINISHED", FireMissionEvent.MARK_RETURN_COMPLETED),
        Map.entry("FAILED", FireMissionEvent.FORCE_FAIL),
        Map.entry("ABNORMAL", FireMissionEvent.FORCE_FAIL)
    );

    private static final Map<String, FireMissionEvent> PHASE_EVENTS = Map.ofEntries(
        Map.entry("FLYING", FireMissionEvent.START_DELIVERY),
        Map.entry("HOVERING", FireMissionEvent.MARK_RELEASE_PENDING),
        Map.entry("ARRIVED", FireMissionEvent.MARK_RELEASE_PENDING),
        Map.entry("RETURNING", FireMissionEvent.MARK_RETURNING),
        Map.entry("FINISHED", FireMissionEvent.MARK_RETURN_COMPLETED),
        Map.entry("COMPLETED", FireMissionEvent.MARK_RETURN_COMPLETED),
        Map.entry("ABNORMAL", FireMissionEvent.FORCE_FAIL),
        Map.entry("FAILED", FireMissionEvent.FORCE_FAIL)
    );

    private DeliveryTaskStatusMapper() {
    }

    public static Optional<FireMissionEvent> toMissionEvent(DeliveryTaskStatus status) {
        if (status == null) {
            return Optional.empty();
        }
        if (status.getTaskCode() != null && status.getTaskCode() != 0) {
            return Optional.of(FireMissionEvent.FORCE_FAIL);
        }
        FireMissionEvent event = STATUS_EVENTS.get(normalize(status.getStatus()));
        if (event != null) {
            return Optional.of(event);
        }
        return Optional.ofNullable(PHASE_EVENTS.get(normalize(status.getPhase())));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
