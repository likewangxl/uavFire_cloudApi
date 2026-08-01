package com.yx.uavfire.fc100.event.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.event.dao.AgentFireReportMapper;
import com.yx.uavfire.fc100.event.dao.FireEventHistoryMapper;
import com.yx.uavfire.fc100.event.dao.FireEventMapper;
import com.yx.uavfire.fc100.event.model.entity.AgentFireReportEntity;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.event.model.entity.FireEventHistoryEntity;
import com.yx.uavfire.fc100.event.model.param.AgentFireReportParam;
import com.yx.uavfire.fc100.event.service.AgentFireNotificationTransactionParticipant;
import com.yx.uavfire.fc100.event.service.AgentFireReportIngress;
import com.yx.uavfire.fc100.event.service.AgentFireTaskBindingResolver;
import com.yx.uavfire.fc100.event.notification.dao.FireNotificationOutboxMapper;
import com.yx.uavfire.fc100.event.notification.model.FireNotificationOutboxEntity;
import com.yx.uavfire.fc100.event.notification.service.DurableAgentFireNotificationTransactionParticipant;
import com.yx.uavfire.fc100.event.notification.service.FireNotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.apache.ibatis.annotations.Update;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PersistentAgentFireReportIngressTest {
    private final ObjectMapper json = new ObjectMapper();
    private final FireEventMapper events = mock(FireEventMapper.class);
    private final FireEventHistoryMapper histories = mock(FireEventHistoryMapper.class);
    private final AgentFireReportMapper reports = mock(AgentFireReportMapper.class);
    private final AgentFireNotificationTransactionParticipant notifications = mock(AgentFireNotificationTransactionParticipant.class);
    private final AgentFireTaskBindingResolver taskBindings = mock(AgentFireTaskBindingResolver.class);
    private final Map<String, FireEventEntity> eventRows = new HashMap<>();
    private final Map<String, AgentFireReportEntity> reportRows = new HashMap<>();
    private final List<FireEventHistoryEntity> historyRows = new ArrayList<>();
    private final Map<String, FireNotificationOutboxEntity> outboxRows = new HashMap<>();
    private PersistentAgentFireReportIngress ingress;

    @BeforeEach void setUp() {
        when(events.selectByEventId(any())).thenAnswer(i -> eventRows.get(i.getArgument(0)));
        when(events.selectByEventIdForUpdate(any())).thenAnswer(i -> eventRows.get(i.getArgument(0)));
        when(events.insert(any())).thenAnswer(i -> {
            FireEventEntity e = i.getArgument(0); e.setId((long) eventRows.size() + 1); eventRows.put(e.getEventId(), e); return 1;
        });
        when(events.updateById(any())).thenAnswer(i -> { FireEventEntity e = i.getArgument(0); eventRows.put(e.getEventId(), e); return 1; });
        when(events.advanceAgentSequence(any(), anyLong(), anyLong(), any(), any(), any(), anyLong())).thenAnswer(i -> {
            FireEventEntity e = eventRows.get(i.getArgument(0));
            long expected = i.getArgument(1); long next = i.getArgument(2);
            if (e == null || e.getLastAgentSequence() == null || e.getLastAgentSequence() != expected) return 0;
            e.setLastAgentSequence(next); e.setDetectionStatus(i.getArgument(3));
            if (i.getArgument(4) != null) e.setLocationStatus(i.getArgument(4));
            if (i.getArgument(5) != null) e.setFlightStatus(i.getArgument(5));
            return 1;
        });
        when(reports.selectByEventAndSequence(any(), anyLong())).thenAnswer(i -> reportRows.get(key(i.getArgument(0), i.getArgument(1))));
        when(reports.selectByEventAndSequenceForUpdate(any(), anyLong())).thenAnswer(i -> reportRows.get(key(i.getArgument(0), i.getArgument(1))));
        when(reports.insertImmutable(any())).thenAnswer(i -> {
            AgentFireReportEntity r = i.getArgument(0); reportRows.put(key(r.getEventId(), r.getSequence()), r); return 1;
        });
        when(histories.insert(any())).thenAnswer(i -> { historyRows.add(i.getArgument(0)); return 1; });
        when(notifications.enqueue(any(), any(), anyInt())).thenReturn(true);
        when(taskBindings.resolveForInitialReport("task-1", "drone-1", 1_000L))
            .thenReturn(new AgentFireTaskBindingResolver.Binding("workspace-real", "task-1", "drone-1"));
        ingress = new PersistentAgentFireReportIngress(events, histories, reports, notifications,
            (Clock) () -> 2_000L, json, taskBindings);
    }

    @Test void createsExactlyOneInitialEventAndExactDuplicateHasNoSideEffects() throws Exception {
        AgentFireReportParam initial = stage(1, "VISUAL_CONFIRMED");
        initial.setConfidence(.91); initial.setLocationStatus("LASER_LOCATING");
        AgentFireReportIngress.Result first = accept(initial);
        assertEquals(AgentFireReportIngress.Result.Status.COMMITTED, first.getStatus());
        assertTrue(first.isNotificationQueued());
        FireEventEntity event = eventRows.get("event-1");
        assertEquals("workspace-real", event.getWorkspaceId());
        assertEquals(1L, event.getLastAgentSequence()); assertEquals(1, event.getNotificationVersion());
        assertNull(event.getLat()); assertNull(event.getLng()); assertEquals(0, event.getAgentSpatialMergeEligible());
        assertEquals(1, historyRows.size()); assertEquals(1, reportRows.size());

        AgentFireReportIngress.Result duplicate = accept(initial);
        assertEquals(AgentFireReportIngress.Result.Status.EXACT_DUPLICATE, duplicate.getStatus());
        assertEquals(1, historyRows.size()); assertEquals(1, reportRows.size());
        verify(notifications, times(1)).enqueue(any(), any(), eq(1));
    }

    @Test void sameSequenceWithDifferentExactBytesConflicts() throws Exception {
        AgentFireReportParam initial = initial();
        byte[] raw = json.writeValueAsBytes(initial);
        assertEquals(AgentFireReportIngress.Result.Status.COMMITTED,
            ingress.accept(initial, raw, sha(raw)).getStatus());
        byte[] changedBytes = (" \n" + new String(raw, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        assertEquals(AgentFireReportIngress.Result.Status.CONFLICT,
            ingress.accept(initial, changedBytes, sha(changedBytes)).getStatus());
        assertEquals(1, historyRows.size());
    }

    @Test void rejectsGapOutOfOrderAndImmutableIdentityMismatch() throws Exception {
        accept(initial());
        assertEquals(AgentFireReportIngress.Result.Status.CONFLICT, accept(stage(3, "HOVER_VERIFYING")).getStatus());
        AgentFireReportParam mismatched = stage(2, "HOLD_REQUESTED"); mismatched.setTaskId("other-task");
        assertEquals(AgentFireReportIngress.Result.Status.CONFLICT, accept(mismatched).getStatus());
        assertEquals(1L, eventRows.get("event-1").getLastAgentSequence());
    }

    @Test void initialReportWithoutAuthoritativeActiveTaskBindingConflictsWithoutCreatingEvent() throws Exception {
        when(taskBindings.resolveForInitialReport(any(), any(), anyLong())).thenReturn(null);
        AgentFireReportIngress.Result result = accept(initial());
        assertEquals(AgentFireReportIngress.Result.Status.CONFLICT, result.getStatus());
        assertTrue(eventRows.isEmpty()); assertTrue(reportRows.isEmpty()); assertTrue(historyRows.isEmpty());
        verify(notifications, never()).enqueue(any(), any(), anyInt());
    }

    @Test void optimisticConcurrencyLoserUsesCurrentReadAndReturnsExactDuplicate() throws Exception {
        accept(initial());
        AgentFireReportParam hold = progress(2, "HOLD_REQUESTED", "HOLD_REQUESTED");
        byte[] raw = json.writeValueAsBytes(hold); String hash = sha(raw);
        AgentFireReportEntity winner = new AgentFireReportEntity(); winner.setEventId("event-1");
        winner.setSequence(2L); winner.setPayloadSha256(hash); winner.setNotificationQueued(0);
        when(events.advanceAgentSequence(eq("event-1"), eq(1L), eq(2L), any(), any(), any(), anyLong())).thenReturn(0);
        when(reports.selectByEventAndSequenceForUpdate("event-1", 2L)).thenReturn(winner);
        AgentFireReportIngress.Result result = ingress.accept(hold, raw, hash);
        assertEquals(AgentFireReportIngress.Result.Status.EXACT_DUPLICATE, result.getStatus());
        verify(reports).selectByEventAndSequenceForUpdate("event-1", 2L);
        assertEquals(1, historyRows.size());
    }

    @Test void sequenceAdvanceIsAnAtomicCompareAndSetNotAProcessLocalLock() throws Exception {
        Method method = FireEventMapper.class.getMethod("advanceAgentSequence", String.class, long.class,
            long.class, String.class, String.class, String.class, long.class);
        Update update = method.getAnnotation(Update.class);
        assertNotNull(update);
        String sql = String.join(" ", update.value()).toLowerCase(java.util.Locale.ROOT);
        assertTrue(sql.contains("where event_id=#{eventid} and last_agent_sequence=#{expectedsequence}"));
        assertFalse(sql.contains("get_lock"));
    }

    @Test void progressWritesHistoryButDoesNotIncrementNotificationVersion() throws Exception {
        accept(initial());
        AgentFireReportParam hold = stage(2, "HOLD_REQUESTED"); hold.setLocationStatus("LASER_LOCATING"); hold.setFlightStatus("HOLD_REQUESTED");
        AgentFireReportIngress.Result result = accept(hold);
        assertFalse(result.isNotificationQueued());
        assertEquals(1, eventRows.get("event-1").getNotificationVersion());
        assertEquals(2, historyRows.size()); assertEquals("AGENT_HOLD_REQUESTED", historyRows.get(1).getAction());
        verify(notifications, times(1)).enqueue(any(), any(), anyInt());
    }

    @Test void degradedTerminalKeepsFirePointNullAndAircraftIndependent() throws Exception {
        advanceToLaser();
        AgentFireReportParam terminal = stage(6, "RESULT_DURABLE");
        terminal.setLocationStatus("DEGRADED_OSD"); terminal.setGeoMethod("AIRCRAFT_OBSERVATION");
        terminal.setDegradedReason("LASER_SAMPLES_INVALID"); terminal.setAircraft(point(34.1, 108.9, 120.0));
        assertTrue(accept(terminal).isNotificationQueued());
        FireEventEntity e = eventRows.get("event-1");
        assertNull(e.getLat()); assertNull(e.getLng()); assertNull(e.getAlt());
        assertEquals(34.1, e.getAircraftLat()); assertEquals(108.9, e.getAircraftLng());
        assertEquals("DEGRADED_OSD", e.getGeoQuality()); assertEquals(0, e.getAgentSpatialMergeEligible());
        assertEquals(2, e.getNotificationVersion());

        AgentFireReportParam regression = stage(7, "RESULT_DURABLE");
        regression.setLocationStatus("DEGRADED_OSD");
        assertEquals(AgentFireReportIngress.Result.Status.CONFLICT, accept(regression).getStatus());
    }

    @Test void preciseStoresServerMedianAndOnlyThenBecomesSpatiallyEligible() throws Exception {
        advanceToLaser();
        AgentFireReportParam terminal = precise(6);
        terminal.setFireLat(34.00001); terminal.setFireLng(108.00001); terminal.setFireAlt(101.0);
        assertEquals(AgentFireReportIngress.Result.Status.COMMITTED, accept(terminal).getStatus());
        FireEventEntity e = eventRows.get("event-1");
        assertEquals(34.0, e.getLat()); assertEquals(108.0, e.getLng()); assertEquals(100.0, e.getAlt());
        assertEquals("PRECISE", e.getGeoQuality()); assertEquals(1, e.getAgentSpatialMergeEligible());
        assertEquals(2, e.getNotificationVersion());
    }

    @Test void preciseRevalidationRejectsScatterAndClientMedianMismatch() throws Exception {
        advanceToLaser();
        AgentFireReportParam scattered = precise(6);
        scattered.getLaserSamples().get(2).setLat(35.0);
        assertThrows(IllegalArgumentException.class, () -> accept(scattered));
        assertEquals(5L, eventRows.get("event-1").getLastAgentSequence());

        AgentFireReportParam mismatch = precise(6); mismatch.setFireLat(35.0);
        assertThrows(IllegalArgumentException.class, () -> accept(mismatch));
        assertEquals(5L, eventRows.get("event-1").getLastAgentSequence());
    }

    @Test void notificationBearingWriteFailsBeforeReportAndHistoryWhenParticipantUnavailable() throws Exception {
        when(notifications.enqueue(any(), any(), anyInt())).thenThrow(new com.yx.uavfire.fc100.event.service.AgentFireIngressUnavailableException("no outbox"));
        TransactionTemplate tx = new TransactionTemplate(new SnapshotTransactionManager());
        AgentFireReportParam initial = initial();
        assertThrows(com.yx.uavfire.fc100.event.service.AgentFireIngressUnavailableException.class,
            () -> tx.executeWithoutResult(ignored -> {
                try { accept(initial); } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new RuntimeException(e); }
            }));
        assertTrue(eventRows.isEmpty()); assertTrue(reportRows.isEmpty()); assertTrue(historyRows.isEmpty());

        reset(notifications); when(notifications.enqueue(any(), any(), anyInt())).thenReturn(true);
        AgentFireReportIngress.Result retried = tx.execute(ignored -> {
            try { return accept(initial); } catch (Exception e) { throw new RuntimeException(e); }
        });
        assertNotNull(retried); assertEquals(AgentFireReportIngress.Result.Status.COMMITTED, retried.getStatus());
        assertEquals(1, eventRows.size()); assertEquals(1, reportRows.size()); assertEquals(1, historyRows.size());
    }

    @Test void durableOutboxAndRealIngressCommitOrRollbackAsOneTransaction() throws Exception {
        FireNotificationOutboxMapper outbox = mock(FireNotificationOutboxMapper.class);
        FireNotificationDispatcher dispatcher = mock(FireNotificationDispatcher.class);
        when(outbox.selectIdentity(any(), anyInt())).thenAnswer(i -> outboxRows.get(i.getArgument(0) + ":" + i.getArgument(1)));
        when(outbox.selectIdentityForUpdate(any(), anyInt())).thenAnswer(i -> outboxRows.get(i.getArgument(0) + ":" + i.getArgument(1)));
        when(outbox.insertOutbox(any())).thenAnswer(i -> {
            FireNotificationOutboxEntity row = i.getArgument(0);
            outboxRows.put(row.getEventId() + ":" + row.getNotificationVersion(), row);
            return 1;
        });
        AgentFireNotificationTransactionParticipant durable =
            new DurableAgentFireNotificationTransactionParticipant(outbox, json, () -> 2_000L, dispatcher);
        PersistentAgentFireReportIngress realIngress = new PersistentAgentFireReportIngress(
            events, histories, reports, durable, () -> 2_000L, json, taskBindings);
        TransactionTemplate tx = new TransactionTemplate(new SnapshotTransactionManager());
        AgentFireReportParam initial = initial();
        byte[] raw = json.writeValueAsBytes(initial); String hash = sha(raw);

        reset(histories);
        when(histories.insert(any())).thenThrow(new IllegalStateException("injected after outbox insert"));
        assertThrows(IllegalStateException.class,
            () -> tx.execute(ignored -> realIngress.accept(initial, raw, hash)));
        assertTrue(eventRows.isEmpty(), "event rows rolled back: " + eventRows.keySet());
        assertTrue(reportRows.isEmpty(), "report rows rolled back: " + reportRows.keySet());
        assertTrue(historyRows.isEmpty(), "history rows rolled back: " + historyRows.size());
        assertTrue(outboxRows.isEmpty(), "outbox rows rolled back: " + outboxRows.keySet());
        verify(dispatcher, never()).wakeAfterCommit();

        reset(histories);
        when(histories.insert(any())).thenAnswer(i -> { historyRows.add(i.getArgument(0)); return 1; });
        AgentFireReportIngress.Result committed = tx.execute(ignored -> realIngress.accept(initial, raw, hash));
        assertNotNull(committed); assertTrue(committed.isNotificationQueued());
        assertEquals(1, eventRows.size()); assertEquals(1, historyRows.size()); assertEquals(1, reportRows.size());
        assertEquals(1, outboxRows.size());
        verify(dispatcher).wakeAfterCommit();

        AgentFireReportIngress.Result duplicate = tx.execute(ignored -> realIngress.accept(initial, raw, hash));
        assertNotNull(duplicate); assertEquals(AgentFireReportIngress.Result.Status.EXACT_DUPLICATE, duplicate.getStatus());
        assertEquals(1, outboxRows.size()); verify(dispatcher, times(1)).wakeAfterCommit();
    }

    @Test void resumeAfterTerminalIsMonotonicAndProgressOnly() throws Exception {
        advanceToLaser(); accept(precise(6));
        AgentFireReportParam resume = stage(7, "RESUME_REQUESTED"); resume.setFlightStatus("RESUME_REQUESTED");
        accept(resume);
        AgentFireReportParam resumed = stage(8, "MISSION_RESUMED"); resumed.setFlightStatus("MISSION_RESUMED");
        accept(resumed);
        FireEventEntity e = eventRows.get("event-1");
        assertEquals("PRECISE", e.getLocationStatus()); assertEquals(2, e.getNotificationVersion());
        verify(notifications, times(2)).enqueue(any(), any(), anyInt());
    }

    private void advanceToLaser() throws Exception {
        accept(initial());
        accept(progress(2, "HOLD_REQUESTED", "HOLD_REQUESTED"));
        accept(progress(3, "HOVER_VERIFYING", "HOVERING"));
        accept(progress(4, "TARGET_ALIGNING", "TARGET_ALIGNING"));
        accept(progress(5, "LASER_MEASURING", "LASER_MEASURING"));
    }
    private AgentFireReportParam progress(long sequence, String state, String flight) {
        AgentFireReportParam p = stage(sequence, state); p.setLocationStatus("LASER_LOCATING"); p.setFlightStatus(flight); return p;
    }
    private AgentFireReportParam initial() {
        AgentFireReportParam p = stage(1, "VISUAL_CONFIRMED"); p.setConfidence(.91); p.setLocationStatus("LASER_LOCATING"); return p;
    }
    private AgentFireReportParam precise(long sequence) {
        AgentFireReportParam p = stage(sequence, "RESULT_DURABLE"); p.setLocationStatus("PRECISE");
        p.setGeoMethod("LASER_RANGEFINDER"); p.setFireLat(34.0); p.setFireLng(108.0); p.setFireAlt(100.0); p.setErrorRadiusMeters(5.0);
        List<AgentFireReportParam.LaserSample> samples = new ArrayList<>();
        samples.add(sample(33.99999, 107.99999, 99.0)); samples.add(sample(34.0, 108.0, 100.0)); samples.add(sample(34.00001, 108.00001, 101.0));
        p.setLaserSamples(samples); return p;
    }
    private AgentFireReportParam.LaserSample sample(double lat, double lng, double alt) {
        AgentFireReportParam.LaserSample s = new AgentFireReportParam.LaserSample(); s.setStatus("NORMAL");
        s.setRangeMeters(60.0); s.setLat(lat); s.setLng(lng); s.setAlt(alt); s.setEventTimestamp(1_000L); return s;
    }
    private AgentFireReportParam stage(long sequence, String state) {
        AgentFireReportParam p = new AgentFireReportParam();
        p.setAgentId("agent-1"); p.setDroneSn("drone-1"); p.setTaskId("task-1"); p.setEventId("event-1"); p.setSessionId("session-1");
        p.setSequence(sequence); p.setEventTimestamp(1_000L); p.setState(state); p.setDetectionKind("FIRE");
        AgentFireReportParam.Roi roi = new AgentFireReportParam.Roi(); roi.setX(.4); roi.setY(.4); roi.setWidth(.2); roi.setHeight(.2); p.setVisibleRoi(roi);
        p.setModelVersion("visible-fire-wechat-best2-20260728"); p.setModelHash("957bec7a567ce1f57f9a57187a6b085c7c95149b889773479d018e3ed5e9f650");
        p.setPolicyVersion("agent-visible-v1"); p.setInputSize(960); p.setRuntime("NCNN"); p.setSourceGeneration(7L); p.setCoordinatorGeneration(9L);
        return p;
    }
    private AgentFireReportParam.Point point(double lat, double lng, double alt) {
        AgentFireReportParam.Point p = new AgentFireReportParam.Point(); p.setLat(lat); p.setLng(lng); p.setAlt(alt); return p;
    }
    private AgentFireReportIngress.Result accept(AgentFireReportParam p) throws Exception {
        byte[] raw = json.writeValueAsBytes(p); return ingress.accept(p, raw, sha(raw));
    }
    private String sha(byte[] raw) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw); StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format("%02x", b & 0xff)); return out.toString();
    }
    private String key(String eventId, long sequence) { return eventId + ":" + sequence; }

    private final class SnapshotTransactionManager extends AbstractPlatformTransactionManager {
        private Map<String, FireEventEntity> eventSnapshot;
        private Map<String, AgentFireReportEntity> reportSnapshot;
        private List<FireEventHistoryEntity> historySnapshot;
        private Map<String, FireNotificationOutboxEntity> outboxSnapshot;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            eventSnapshot = new HashMap<>(eventRows); reportSnapshot = new HashMap<>(reportRows);
            historySnapshot = new ArrayList<>(historyRows);
            outboxSnapshot = new HashMap<>(outboxRows);
        }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) {
            eventRows.clear(); eventRows.putAll(eventSnapshot);
            reportRows.clear(); reportRows.putAll(reportSnapshot);
            historyRows.clear(); historyRows.addAll(historySnapshot);
            outboxRows.clear(); outboxRows.putAll(outboxSnapshot);
        }
    }
}
