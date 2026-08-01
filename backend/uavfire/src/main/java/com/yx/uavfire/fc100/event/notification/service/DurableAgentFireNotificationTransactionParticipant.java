package com.yx.uavfire.fc100.event.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.event.model.param.AgentFireReportParam;
import com.yx.uavfire.fc100.event.notification.dao.FireNotificationOutboxMapper;
import com.yx.uavfire.fc100.event.notification.model.FireNotificationOutboxEntity;
import com.yx.uavfire.fc100.event.notification.model.FireNotificationPayload;
import com.yx.uavfire.fc100.event.service.AgentFireNotificationTransactionParticipant;
import com.yx.uavfire.fc100.event.service.AgentFireNotificationConflictException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class DurableAgentFireNotificationTransactionParticipant
        implements AgentFireNotificationTransactionParticipant {
    public static final String INITIAL_MESSAGE = "发现疑似火情，正在精确定位";
    public static final String PRECISE_MESSAGE = "目标已完成激光定位";
    public static final String DEGRADED_MESSAGE = "视觉火情已保存，激光定位失败，已记录飞机观测位置";

    private final FireNotificationOutboxMapper mapper;
    private final ObjectMapper json;
    private final Clock clock;
    private final FireNotificationDispatcher dispatcher;

    public DurableAgentFireNotificationTransactionParticipant(FireNotificationOutboxMapper mapper,
                                                              ObjectMapper json,
                                                              Clock clock,
                                                              FireNotificationDispatcher dispatcher) {
        this.mapper = mapper; this.json = json; this.clock = clock; this.dispatcher = dispatcher;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enqueue(FireEventEntity event, AgentFireReportParam report, int notificationVersion) {
        FireNotificationOutboxEntity desired = build(event, report, notificationVersion);
        FireNotificationOutboxEntity existing = mapper.selectIdentity(event.getEventId(), notificationVersion);
        if (existing != null) return exact(existing, desired);
        try {
            if (mapper.insertOutbox(desired) != 1) return false;
        } catch (DuplicateKeyException raced) {
            existing = mapper.selectIdentityForUpdate(event.getEventId(), notificationVersion);
            return existing != null && exact(existing, desired);
        }
        wakeOnlyAfterCommit();
        return true;
    }

    private FireNotificationOutboxEntity build(FireEventEntity event, AgentFireReportParam report, int version) {
        if (version != 1 && version != 2) {
            throw new IllegalStateException("unsupported fire notification version " + version);
        }
        String type = version == 1 ? "INITIAL" : report.getLocationStatus();
        if (version == 2 && !"PRECISE".equals(type) && !"DEGRADED_OSD".equals(type)) {
            throw new IllegalStateException("terminal fire notification requires a durable location result");
        }
        String message = "INITIAL".equals(type) ? INITIAL_MESSAGE :
            ("PRECISE".equals(type) ? PRECISE_MESSAGE : DEGRADED_MESSAGE);
        FireNotificationPayload payload = new FireNotificationPayload();
        payload.setNotificationId(event.getEventId() + ":" + version);
        payload.setEventId(event.getEventId()); payload.setNotificationVersion(version);
        payload.setWorkspaceId(event.getWorkspaceId()); payload.setTaskId(report.getTaskId());
        payload.setDroneSn(report.getDroneSn()); payload.setDetectionKind(report.getDetectionKind());
        payload.setState(report.getState()); payload.setLocationStatus(event.getLocationStatus());
        payload.setFlightStatus(event.getFlightStatus()); payload.setEventTimestamp(report.getEventTimestamp());
        payload.setMessage(message);
        if ("PRECISE".equals(type)) {
            payload.setFireLat(event.getLat()); payload.setFireLng(event.getLng()); payload.setFireAlt(event.getAlt());
        } else if ("DEGRADED_OSD".equals(type)) {
            payload.setAircraftLat(event.getAircraftLat()); payload.setAircraftLng(event.getAircraftLng());
            payload.setAircraftAlt(event.getAircraftAlt());
        }
        String serialized = write(payload);
        long now = clock.now();
        FireNotificationOutboxEntity row = new FireNotificationOutboxEntity();
        row.setEventId(event.getEventId()); row.setNotificationVersion(version);
        row.setNotificationId(payload.getNotificationId()); row.setWorkspaceId(event.getWorkspaceId());
        row.setNotificationType(type); row.setPayload(serialized); row.setPayloadSha256(sha256(serialized));
        row.setStatus("PENDING"); row.setAttempts(0); row.setNextAttemptTime(now);
        row.setCreateTime(now); row.setUpdateTime(now);
        return row;
    }

    private boolean exact(FireNotificationOutboxEntity actual, FireNotificationOutboxEntity desired) {
        if (eq(actual.getNotificationId(), desired.getNotificationId()) &&
            eq(actual.getWorkspaceId(), desired.getWorkspaceId()) &&
            eq(actual.getNotificationType(), desired.getNotificationType()) &&
            eq(actual.getPayloadSha256(), desired.getPayloadSha256()) && eq(actual.getPayload(), desired.getPayload())) return true;
        throw new AgentFireNotificationConflictException(
            "conflicting fire notification identity " + desired.getNotificationId());
    }

    private void wakeOnlyAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { dispatcher.wakeAfterCommit(); }
            });
        } else {
            dispatcher.wakeAfterCommit();
        }
    }

    private String write(FireNotificationPayload payload) {
        try { return json.writeValueAsString(payload); }
        catch (JsonProcessingException e) { throw new IllegalStateException("cannot serialize fire notification", e); }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean eq(Object a, Object b) { return a == null ? b == null : a.equals(b); }
}
