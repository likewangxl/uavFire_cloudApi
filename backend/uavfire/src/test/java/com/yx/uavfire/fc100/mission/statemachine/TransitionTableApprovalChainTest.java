package com.yx.uavfire.fc100.mission.statemachine;

import com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransitionTableApprovalChainTest {

    @Test
    void confirmedDraftCanBeApprovedFromWaitingReviewOrHistoricalCreated() {
        assertEquals(FireMissionStatus.APPROVED,
            TransitionTable.nextStatus(FireMissionStatus.WAITING_REVIEW, FireMissionEvent.APPROVE).orElseThrow());
        assertEquals(FireMissionStatus.APPROVED,
            TransitionTable.nextStatus(FireMissionStatus.CREATED, FireMissionEvent.APPROVE).orElseThrow());
        assertTrue(TransitionTable.allowedEvents(FireMissionStatus.CREATED).contains(FireMissionEvent.APPROVE));
        assertTrue(TransitionTable.allowedEvents(FireMissionStatus.WAITING_REVIEW).contains(FireMissionEvent.APPROVE));
    }

    @Test
    void illegalMissionJumpsStayForbidden() {
        assertTrue(TransitionTable.nextStatus(FireMissionStatus.CREATED, FireMissionEvent.START_DELIVERY).isEmpty());
        assertTrue(TransitionTable.nextStatus(FireMissionStatus.WAITING_REVIEW, FireMissionEvent.GEN_WP).isEmpty());
        assertTrue(TransitionTable.nextStatus(FireMissionStatus.APPROVED, FireMissionEvent.APPROVE).isEmpty());
        assertTrue(TransitionTable.nextStatus(FireMissionStatus.IN_PROGRESS, FireMissionEvent.CANCEL).isEmpty());
        assertTrue(TransitionTable.nextStatus(FireMissionStatus.ARCHIVED, FireMissionEvent.FORCE_FAIL).isEmpty());
        assertTrue(TransitionTable.nextStatus(FireMissionStatus.PAYLOAD_RELEASED, FireMissionEvent.CONFIRM_RELEASE).isEmpty());
    }
}
