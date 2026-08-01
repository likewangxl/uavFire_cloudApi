package com.yx.uavfire.firedetection;

import com.yx.uavfire.manage.model.dto.DualStreamCommandDTO;
import com.yx.uavfire.manage.model.dto.DualStreamLiveGroupDTO;
import com.yx.uavfire.manage.service.IDualStreamService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FireDetectionServiceTest {

    @Test
    void startForDrone_queuesAgentArmWithoutClaimingDetectorIsRunning() {
        IDualStreamService commands = mock(IDualStreamService.class);
        when(commands.issueCommand("DRONE-001", "visible-detector-arm"))
                .thenReturn(new DualStreamCommandDTO());
        FireDetectionService service = new FireDetectionService(commands);

        assertTrue(service.startForDrone("DRONE-001"));
        verify(commands).issueCommand("DRONE-001", "visible-detector-arm");
        assertFalse((Boolean) service.statusForDrone("DRONE-001").get("running"));
        assertEquals("AGENT", service.statusForDrone("DRONE-001").get("executor"));
    }

    @Test
    void statusForDrone_usesObservedAgentHeartbeatNotQueuedCommand() {
        IDualStreamService commands = mock(IDualStreamService.class);
        when(commands.getGroup("DRONE-001")).thenReturn(new DualStreamLiveGroupDTO()
                .setDetectorIntent("ARMED")
                .setDetectorState("ARMED")
                .setDetectorHealth("HEALTHY")
                .setDetectorObservedAt(System.currentTimeMillis()));
        FireDetectionService service = new FireDetectionService(commands);

        Map<String, Object> status = service.statusForDrone("DRONE-001");

        assertTrue((Boolean) status.get("running"));
        assertEquals(false, status.get("heartbeat_stale"));
    }

    @Test
    void statusForDrone_failsClosedWhenAgentHeartbeatIsStale() {
        IDualStreamService commands = mock(IDualStreamService.class);
        when(commands.getGroup("DRONE-001")).thenReturn(new DualStreamLiveGroupDTO()
                .setDetectorState("ARMED")
                .setDetectorHealth("HEALTHY")
                .setDetectorObservedAt(System.currentTimeMillis() - 60_000));

        Map<String, Object> status = new FireDetectionService(commands).statusForDrone("DRONE-001");

        assertFalse((Boolean) status.get("running"));
        assertEquals(true, status.get("heartbeat_stale"));
        assertEquals("agent-heartbeat-stale", status.get("status_reason"));
    }

    @Test
    void stopForDrone_queuesAgentDisarm() {
        IDualStreamService commands = mock(IDualStreamService.class);
        when(commands.issueCommand("DRONE-001", "visible-detector-disarm"))
                .thenReturn(new DualStreamCommandDTO());

        assertTrue(new FireDetectionService(commands).stopForDrone("DRONE-001"));
        verify(commands).issueCommand("DRONE-001", "visible-detector-disarm");
    }
}
