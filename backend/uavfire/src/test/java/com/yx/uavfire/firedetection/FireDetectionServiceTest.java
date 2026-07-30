package com.yx.uavfire.firedetection;

import com.yx.uavfire.manage.service.IDualStreamService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FireDetectionServiceTest {

    private FireDetectionService newService(AiServiceClient client, FireDetectionActivityTracker tracker) {
        FireDetectionService service = new FireDetectionService(client, tracker);
        ReflectionTestUtils.setField(service, "zlmRtspHost", "127.0.0.1");
        ReflectionTestUtils.setField(service, "zlmRtspPort", 8554);
        return service;
    }

    @Test
    void startForDrone_marksFireDetectionActiveOnSuccess() {
        AiServiceClient client = mock(AiServiceClient.class);
        IDualStreamService dualStreamService = mock(IDualStreamService.class);
        when(client.fireTaskIdForDrone("DRONE-001")).thenReturn("fire-DRONE-001");
        when(client.startDetection(eq("fire-DRONE-001"), eq("DRONE-001"), anyString(), anyString())).thenReturn(true);
        FireDetectionActivityTracker tracker = new FireDetectionActivityTracker();
        FireDetectionService service = newService(client, tracker);
        ReflectionTestUtils.setField(service, "dualStreamService", dualStreamService);

        assertTrue(service.startForDrone("DRONE-001"));
        assertTrue(tracker.isActive("DRONE-001"));
        verify(dualStreamService).issueCommand("DRONE-001", "thermal-monitor-off");
    }

    @Test
    void startForDrone_keepsInactiveWhenStartFails() {
        AiServiceClient client = mock(AiServiceClient.class);
        when(client.fireTaskIdForDrone("DRONE-001")).thenReturn("fire-DRONE-001");
        when(client.startDetection(eq("fire-DRONE-001"), eq("DRONE-001"), anyString(), anyString())).thenReturn(false);
        FireDetectionActivityTracker tracker = new FireDetectionActivityTracker();

        assertFalse(newService(client, tracker).startForDrone("DRONE-001"));
        assertFalse(tracker.isActive("DRONE-001"));
    }

    @Test
    void stopForDrone_marksFireDetectionInactiveEvenIfAiServiceStopFails() {
        AiServiceClient client = mock(AiServiceClient.class);
        when(client.fireTaskIdForDrone("DRONE-001")).thenReturn("fire-DRONE-001");
        when(client.stopDetection("fire-DRONE-001")).thenReturn(false);
        FireDetectionActivityTracker tracker = new FireDetectionActivityTracker();
        tracker.markActive("DRONE-001");

        assertFalse(newService(client, tracker).stopForDrone("DRONE-001"));
        assertFalse(tracker.isActive("DRONE-001"));
    }
}
