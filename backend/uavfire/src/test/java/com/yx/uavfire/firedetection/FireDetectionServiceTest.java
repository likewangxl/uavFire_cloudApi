package com.yx.uavfire.firedetection;

import com.yx.uavfire.manage.model.dto.DualStreamCommandDTO;
import com.yx.uavfire.manage.model.dto.DualStreamLiveGroupDTO;
import com.yx.uavfire.manage.service.IDualStreamService;
import com.yx.uavfire.manage.service.impl.DualStreamServiceImpl;
import com.yx.uavfire.manage.model.dto.DualStreamAgentHeartbeatDTO;
import com.yx.uavfire.manage.model.dto.DetectorIntentRecordDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;

class FireDetectionServiceTest {

    @Test
    void startForDrone_queuesAgentArmWithoutClaimingDetectorIsRunning() {
        IDualStreamService commands = mock(IDualStreamService.class);
        when(commands.setDetectorIntent("DRONE-001", true))
                .thenReturn(new DualStreamCommandDTO());
        FireDetectionService service = new FireDetectionService(commands);

        assertTrue(service.startForDrone("DRONE-001"));
        verify(commands).setDetectorIntent("DRONE-001", true);
        assertFalse((Boolean) service.statusForDrone("DRONE-001").get("running"));
        assertEquals("AGENT", service.statusForDrone("DRONE-001").get("executor"));
    }

    @Test
    void statusForDrone_usesObservedAgentHeartbeatNotQueuedCommand() {
        IDualStreamService commands = mock(IDualStreamService.class);
        when(commands.getGroup("DRONE-001")).thenReturn(new DualStreamLiveGroupDTO()
                .setConnectionState("STREAMING")
                .setSessionState("RUNNING")
                .setDetectorIntent("ARMED")
                .setDetectorState("ARMED")
                .setDetectorHealth("HEALTHY")
                .setDetectorIntentVersion(4L)
                .setDetectorObservedAt(System.currentTimeMillis()));
        when(commands.getDetectorIntent("DRONE-001"))
                .thenReturn(new DetectorIntentRecordDTO("ARMED", 4L));
        FireDetectionService service = new FireDetectionService(commands);

        Map<String, Object> status = service.statusForDrone("DRONE-001");

        assertTrue((Boolean) status.get("running"));
        assertEquals(false, status.get("heartbeat_stale"));
    }

    @Test
    void statusForDrone_failsClosedWhenObservedArmConflictsWithDesiredDisarm() {
        IDualStreamService commands = mock(IDualStreamService.class);
        when(commands.getGroup("DRONE-001")).thenReturn(new DualStreamLiveGroupDTO()
                .setConnectionState("STREAMING").setSessionState("RUNNING")
                .setDetectorIntent("ARMED").setDetectorState("ARMED").setDetectorHealth("HEALTHY")
                .setDetectorIntentVersion(6L).setDetectorObservedAt(System.currentTimeMillis()));
        when(commands.getDetectorIntent("DRONE-001"))
                .thenReturn(new DetectorIntentRecordDTO("DISARMED", 5L));

        Map<String, Object> status = new FireDetectionService(commands).statusForDrone("DRONE-001");

        assertFalse((Boolean) status.get("running"));
        assertEquals("detector-authority-conflict", status.get("status_reason"));
    }

    @Test
    void statusForDrone_failsClosedWhenDesiredAuthorityCannotBeRead() {
        IDualStreamService commands = mock(IDualStreamService.class);
        when(commands.getGroup("DRONE-001")).thenReturn(new DualStreamLiveGroupDTO()
                .setConnectionState("STREAMING").setSessionState("RUNNING")
                .setDetectorIntent("ARMED").setDetectorState("ARMED").setDetectorHealth("HEALTHY")
                .setDetectorIntentVersion(6L).setDetectorObservedAt(System.currentTimeMillis()));

        Map<String, Object> status = new FireDetectionService(commands).statusForDrone("DRONE-001");

        assertFalse((Boolean) status.get("running"));
        assertEquals("detector-authority-unavailable", status.get("status_reason"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void statusForDrone_usesDetectorFieldsRestoredThroughRealRedisGroupCopy() {
        Map<String, String> redis = new ConcurrentHashMap<>();
        redis.put("dual-stream:detector-intent:DRONE-REDIS", "{\"intent\":\"ARMED\",\"version\":3}");
        redis.put("dual-stream:detector-intent-version:DRONE-REDIS", "3");
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        when(values.get(any(String.class))).thenAnswer(invocation -> redis.get(invocation.getArgument(0)));
        when(template.execute(any(RedisScript.class), anyList(), any(String.class))).thenAnswer(invocation -> {
            List<String> keys = invocation.getArgument(1);
            String snapshot = invocation.getArgument(2);
            redis.put(keys.get(0), snapshot);
            return snapshot;
        });
        doAnswer(invocation -> {
            redis.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(values).set(any(String.class), any(String.class));

        DualStreamServiceImpl writer = realService(template);
        writer.acceptHeartbeat("DRONE-REDIS", new DualStreamAgentHeartbeatDTO()
                .setDroneSn("DRONE-REDIS")
                .setConnectionState("STREAMING")
                .setSessionState("RUNNING")
                .setDetectorIntent("ARMED")
                .setDetectorState("ARMED")
                .setDetectorHealth("HEALTHY")
                .setDetectorIntentVersion(3L));

        Map<String, Object> status = new FireDetectionService(realService(template)).statusForDrone("DRONE-REDIS");
        assertTrue((Boolean) status.get("running"));
        assertEquals(3L, status.get("detector_intent_version"));
    }

    private DualStreamServiceImpl realService(StringRedisTemplate template) {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", template);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        return service;
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
    void statusForDrone_rejectsContradictoryOrDisconnectedHeartbeat() {
        IDualStreamService commands = mock(IDualStreamService.class);
        DualStreamLiveGroupDTO group = new DualStreamLiveGroupDTO()
                .setConnectionState("ERROR")
                .setSessionState("RUNNING")
                .setDetectorIntent("DISARMED")
                .setDetectorState("ARMED")
                .setDetectorHealth("HEALTHY")
                .setDetectorIntentVersion(1L)
                .setDetectorObservedAt(System.currentTimeMillis());
        when(commands.getGroup("DRONE-001")).thenReturn(group);
        when(commands.getDetectorIntent("DRONE-001"))
                .thenReturn(new DetectorIntentRecordDTO("DISARMED", 1L));

        Map<String, Object> status = new FireDetectionService(commands).statusForDrone("DRONE-001");

        assertFalse((Boolean) status.get("running"));
        assertEquals("invalid-agent-detector-state", status.get("status_reason"));
    }

    @Test
    void stopForDrone_queuesAgentDisarm() {
        IDualStreamService commands = mock(IDualStreamService.class);
        when(commands.setDetectorIntent("DRONE-001", false))
                .thenReturn(new DualStreamCommandDTO());

        assertTrue(new FireDetectionService(commands).stopForDrone("DRONE-001"));
        verify(commands).setDetectorIntent("DRONE-001", false);
    }
}
