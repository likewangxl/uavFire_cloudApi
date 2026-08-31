package com.yx.uavfire.firedetection;

import com.yx.uavfire.manage.model.dto.DualStreamCommandDTO;
import com.yx.uavfire.manage.service.IDualStreamService;
import com.yx.uavfire.msdk.model.MsdkDeviceStateDTO;
import com.yx.uavfire.msdk.model.PayloadCapabilityDTO;
import com.yx.uavfire.msdk.service.MsdkDeviceStateService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FireDetectionServiceTest {

    private FireDetectionService newService(FireDetectionActivityTracker tracker, IDualStreamService commands) {
        FireDetectionService service = new FireDetectionService(tracker);
        ReflectionTestUtils.setField(service, "dualStreamService", commands);
        return service;
    }

    @Test
    void startForDrone_queuesAgentInferenceAndMarksActive() {
        IDualStreamService commands = mock(IDualStreamService.class);
        when(commands.issueCommand("DRONE-001", "thermal-monitor-off"))
                .thenReturn(new DualStreamCommandDTO());
        when(commands.issueCommand("DRONE-001", "visible-ai-on"))
                .thenReturn(new DualStreamCommandDTO());
        FireDetectionActivityTracker tracker = new FireDetectionActivityTracker();
        FireDetectionService service = newService(tracker, commands);

        assertTrue(service.startForDrone("DRONE-001"));
        assertTrue(tracker.isActive("DRONE-001"));
        InOrder order = inOrder(commands);
        order.verify(commands).issueCommand("DRONE-001", "thermal-monitor-off");
        order.verify(commands).issueCommand("DRONE-001", "visible-ai-on");
    }

    @Test
    void startForDrone_keepsInactiveWhenAgentCommandCannotBeQueued() {
        IDualStreamService commands = mock(IDualStreamService.class);
        FireDetectionActivityTracker tracker = new FireDetectionActivityTracker();

        assertFalse(newService(tracker, commands).startForDrone("DRONE-001"));
        assertFalse(tracker.isActive("DRONE-001"));
    }

    @Test
    void startForM300_requiresSelectedVisibleLivePayloadButNotClosedLoopFlag() {
        IDualStreamService commands = mock(IDualStreamService.class);
        when(commands.issueCommand("M300-001", "thermal-monitor-off"))
                .thenReturn(new DualStreamCommandDTO());
        when(commands.issueCommand("M300-001", "visible-ai-on"))
                .thenReturn(new DualStreamCommandDTO());
        MsdkDeviceStateService stateService = mock(MsdkDeviceStateService.class);
        MsdkDeviceStateDTO state = new MsdkDeviceStateDTO()
                .setAircraftModelKey("M300")
                .setSelectedPayloadPositionIndex(0)
                .setFireClosedLoopReady(false)
                .setPayloads(List.of(new PayloadCapabilityDTO()
                        .setPayloadPositionIndex(0)
                        .setVisibleSupported(true)
                        .setLiveStreamSupported(true)));
        when(stateService.get("M300-001")).thenReturn(Optional.of(state));
        FireDetectionService service = newService(new FireDetectionActivityTracker(), commands);
        ReflectionTestUtils.setField(service, "msdkDeviceStateService", stateService);

        assertTrue(service.startForDrone("M300-001"));
    }

    @Test
    void stopForDrone_marksInactiveAndQueuesAgentStop() {
        IDualStreamService commands = mock(IDualStreamService.class);
        when(commands.issueCommand("DRONE-001", "visible-ai-off"))
                .thenReturn(new DualStreamCommandDTO());
        FireDetectionActivityTracker tracker = new FireDetectionActivityTracker();
        tracker.markActive("DRONE-001");
        FireDetectionService service = newService(tracker, commands);

        assertTrue(service.stopForDrone("DRONE-001"));
        assertFalse(tracker.isActive("DRONE-001"));
        verify(commands).issueCommand("DRONE-001", "visible-ai-off");
        verify(commands).issueCommand("DRONE-001", "thermal-monitor-off");
    }
}
