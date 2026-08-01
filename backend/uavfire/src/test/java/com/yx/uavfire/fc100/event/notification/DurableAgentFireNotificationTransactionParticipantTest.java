package com.yx.uavfire.fc100.event.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.event.model.param.AgentFireReportParam;
import com.yx.uavfire.fc100.event.notification.dao.FireNotificationOutboxMapper;
import com.yx.uavfire.fc100.event.notification.model.FireNotificationOutboxEntity;
import com.yx.uavfire.fc100.event.notification.service.DurableAgentFireNotificationTransactionParticipant;
import com.yx.uavfire.fc100.event.notification.service.FireNotificationDispatcher;
import com.yx.uavfire.fc100.event.service.AgentFireNotificationConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DurableAgentFireNotificationTransactionParticipantTest {
    private final FireNotificationOutboxMapper mapper = mock(FireNotificationOutboxMapper.class);
    private final FireNotificationDispatcher dispatcher = mock(FireNotificationDispatcher.class);
    private final ObjectMapper json = new ObjectMapper();
    private final DurableAgentFireNotificationTransactionParticipant participant =
        new DurableAgentFireNotificationTransactionParticipant(mapper, json, () -> 2_000L, dispatcher);

    @BeforeEach void beginSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test void initialFireUsesExactChinesePayloadAndWakesOnlyAfterCommit() throws Exception {
        when(mapper.insertOutbox(any())).thenReturn(1);
        assertTrue(participant.enqueue(event("FIRE", "LASER_LOCATING"), report("FIRE", "LASER_LOCATING"), 1));
        verify(dispatcher, never()).wakeAfterCommit();

        FireNotificationOutboxEntity row = inserted();
        JsonNode payload = json.readTree(row.getPayload());
        assertEquals("event-1:1", payload.get("notificationId").asText());
        assertEquals("发现疑似火情，正在精确定位", payload.get("message").asText());
        assertEquals("workspace-1", payload.get("workspaceId").asText());
        assertEquals("task-1", payload.get("taskId").asText());
        assertEquals("FIRE", payload.get("detectionKind").asText());
        assertTrue(payload.get("fireLat").isNull());
        assertTrue(payload.get("aircraftLat").isNull());
        assertEquals("INITIAL", row.getNotificationType());
        assertEquals(64, row.getPayloadSha256().length());

        List<TransactionSynchronization> callbacks = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, callbacks.size());
        callbacks.get(0).afterCommit();
        verify(dispatcher).wakeAfterCommit();
    }

    @Test void missingTransactionSynchronizationFailsClosedWithoutInsertOrDirectWake() {
        TransactionSynchronizationManager.clearSynchronization();
        assertThrows(com.yx.uavfire.fc100.event.service.AgentFireIngressUnavailableException.class,
            () -> participant.enqueue(event("FIRE", "LASER_LOCATING"), report("FIRE", "LASER_LOCATING"), 1));
        verify(mapper, never()).insertOutbox(any());
        verify(dispatcher, never()).wakeAfterCommit();
    }

    @Test void preciseSmokeCarriesOnlyGroundFireCoordinatesAndExactMessage() throws Exception {
        when(mapper.insertOutbox(any())).thenReturn(1);
        FireEventEntity event = event("SMOKE", "PRECISE");
        event.setLat(34.1); event.setLng(108.9); event.setAlt(123.0);
        event.setAircraftLat(34.2); event.setAircraftLng(109.0); event.setAircraftAlt(130.0);
        assertTrue(participant.enqueue(event, report("SMOKE", "PRECISE"), 2));
        JsonNode payload = json.readTree(inserted().getPayload());
        assertEquals("目标已完成激光定位", payload.get("message").asText());
        assertEquals("SMOKE", payload.get("detectionKind").asText());
        assertEquals(34.1, payload.get("fireLat").asDouble());
        assertEquals(108.9, payload.get("fireLng").asDouble());
        assertTrue(payload.get("aircraftLat").isNull());
    }

    @Test void degradedSmokeNeverExposesAircraftPositionAsFirePosition() throws Exception {
        when(mapper.insertOutbox(any())).thenReturn(1);
        FireEventEntity event = event("SMOKE", "DEGRADED_OSD");
        event.setAircraftLat(34.2); event.setAircraftLng(109.0); event.setAircraftAlt(130.0);
        assertTrue(participant.enqueue(event, report("SMOKE", "DEGRADED_OSD"), 2));
        JsonNode payload = json.readTree(inserted().getPayload());
        assertEquals("视觉火情已保存，激光定位失败，已记录飞机观测位置", payload.get("message").asText());
        assertTrue(payload.get("fireLat").isNull()); assertTrue(payload.get("fireLng").isNull());
        assertEquals(34.2, payload.get("aircraftLat").asDouble());
        assertEquals(109.0, payload.get("aircraftLng").asDouble());
    }

    @Test void exactExistingIdentityIsIdempotentButDifferentContentConflicts() {
        when(mapper.insertOutbox(any())).thenReturn(1);
        participant.enqueue(event("FIRE", "LASER_LOCATING"), report("FIRE", "LASER_LOCATING"), 1);
        FireNotificationOutboxEntity exact = inserted();
        reset(mapper);
        when(mapper.selectIdentity("event-1", 1)).thenReturn(exact);
        assertTrue(participant.enqueue(event("FIRE", "LASER_LOCATING"), report("FIRE", "LASER_LOCATING"), 1));
        verify(mapper, never()).insertOutbox(any());

        exact.setWorkspaceId("other-workspace");
        assertThrows(AgentFireNotificationConflictException.class,
            () -> participant.enqueue(event("FIRE", "LASER_LOCATING"), report("FIRE", "LASER_LOCATING"), 1));
    }

    @Test void duplicateKeyRaceLocksIdentityAndRejectsConflict() {
        when(mapper.insertOutbox(any())).thenThrow(new DuplicateKeyException("race"));
        FireNotificationOutboxEntity conflict = new FireNotificationOutboxEntity();
        conflict.setNotificationId("event-1:1"); conflict.setWorkspaceId("wrong");
        when(mapper.selectIdentityForUpdate("event-1", 1)).thenReturn(conflict);
        assertThrows(AgentFireNotificationConflictException.class,
            () -> participant.enqueue(event("FIRE", "LASER_LOCATING"), report("FIRE", "LASER_LOCATING"), 1));
    }

    private FireNotificationOutboxEntity inserted() {
        ArgumentCaptor<FireNotificationOutboxEntity> captor = ArgumentCaptor.forClass(FireNotificationOutboxEntity.class);
        verify(mapper).insertOutbox(captor.capture()); return captor.getValue();
    }
    private FireEventEntity event(String kind, String location) {
        FireEventEntity e = new FireEventEntity(); e.setEventId("event-1"); e.setWorkspaceId("workspace-1");
        e.setAgentTaskId("task-1"); e.setDeviceSn("drone-1"); e.setDetectionKind(kind);
        e.setDetectionStatus("RESULT_DURABLE"); e.setLocationStatus(location); e.setFlightStatus("HOVERING"); return e;
    }
    private AgentFireReportParam report(String kind, String location) {
        AgentFireReportParam p = new AgentFireReportParam(); p.setEventId("event-1"); p.setTaskId("task-1");
        p.setDroneSn("drone-1"); p.setDetectionKind(kind); p.setState(location.equals("LASER_LOCATING") ? "VISUAL_CONFIRMED" : "RESULT_DURABLE");
        p.setLocationStatus(location); p.setFlightStatus("HOVERING"); p.setEventTimestamp(1_000L); return p;
    }
}
