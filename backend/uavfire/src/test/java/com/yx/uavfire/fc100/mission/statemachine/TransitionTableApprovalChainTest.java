package com.yx.uavfire.fc100.mission.statemachine;

import org.junit.jupiter.api.Test;

import static com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent.APPROVE;
import static com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent.CANCEL;
import static com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent.PILOT_ACCEPT;
import static com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent.REJECT;
import static com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent.START_DELIVERY;
import static com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus.ACCEPTED_BY_PILOT;
import static com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus.APPROVED;
import static com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus.CANCELLED;
import static com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus.CREATED;
import static com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus.IN_PROGRESS;
import static com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus.SENT_TO_DELIVERY;
import static com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus.WAITING_REVIEW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransitionTableApprovalChainTest {

    @Test
    void createdAndWaitingReviewCanBeApprovedAndIllegalJumpsStayClosed() {
        assertEquals(APPROVED, TransitionTable.nextStatus(CREATED, APPROVE).orElseThrow());
        assertEquals(APPROVED, TransitionTable.nextStatus(WAITING_REVIEW, APPROVE).orElseThrow());
        assertTrue(TransitionTable.nextStatus(CREATED, REJECT).isEmpty());
        assertTrue(TransitionTable.nextStatus(APPROVED, PILOT_ACCEPT).isEmpty());
    }

    @Test
    void pilotAcceptEntersAcceptedByPilotOnlyFromSentToDelivery() {
        assertEquals(ACCEPTED_BY_PILOT, TransitionTable.nextStatus(SENT_TO_DELIVERY, PILOT_ACCEPT).orElseThrow());
        assertEquals(IN_PROGRESS, TransitionTable.nextStatus(ACCEPTED_BY_PILOT, START_DELIVERY).orElseThrow());
        assertEquals(CANCELLED, TransitionTable.nextStatus(ACCEPTED_BY_PILOT, CANCEL).orElseThrow());
        assertTrue(TransitionTable.nextStatus(CREATED, PILOT_ACCEPT).isEmpty());
        assertTrue(TransitionTable.nextStatus(WAITING_REVIEW, PILOT_ACCEPT).isEmpty());
    }
}
