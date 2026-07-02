package com.yx.uavfire.fc100.deliverysync;

import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskStatus;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryTaskStatusMapperTest {

    @Test
    void mapsDeliveryTaskStatusesToMissionEventsInOneTable() {
        assertEquals(FireMissionEvent.CREATE_DELIVERY_TASK, map("CREATED", null, null).orElseThrow());
        assertEquals(FireMissionEvent.START_DELIVERY, map("IN_PROGRESS", "flying", null).orElseThrow());
        assertEquals(FireMissionEvent.MARK_RELEASE_PENDING, map("ARRIVED", "hovering", null).orElseThrow());
        assertEquals(FireMissionEvent.MARK_RETURNING, map("RETURNING", "returning", null).orElseThrow());
        assertEquals(FireMissionEvent.MARK_RETURN_COMPLETED, map("COMPLETED", "finished", 0).orElseThrow());
        assertEquals(FireMissionEvent.FORCE_FAIL, map("FAILED", "abnormal", 500).orElseThrow());
    }

    @Test
    void ignoresUnknownDeliveryTaskStatus() {
        assertTrue(map("PILOT_ACCEPTED", "waiting", null).isEmpty());
    }

    private java.util.Optional<FireMissionEvent> map(String status, String phase, Integer taskCode) {
        DeliveryTaskStatus dto = new DeliveryTaskStatus();
        dto.setStatus(status);
        dto.setPhase(phase);
        dto.setTaskCode(taskCode);
        return DeliveryTaskStatusMapper.toMissionEvent(dto);
    }
}
