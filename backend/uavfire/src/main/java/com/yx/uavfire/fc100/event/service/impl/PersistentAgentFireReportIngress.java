package com.yx.uavfire.fc100.event.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.event.dao.AgentFireReportMapper;
import com.yx.uavfire.fc100.event.dao.FireEventHistoryMapper;
import com.yx.uavfire.fc100.event.dao.FireEventMapper;
import com.yx.uavfire.fc100.event.model.entity.AgentFireReportEntity;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.event.model.entity.FireEventHistoryEntity;
import com.yx.uavfire.fc100.event.model.param.AgentFireReportParam;
import com.yx.uavfire.fc100.event.service.AgentFireIngressUnavailableException;
import com.yx.uavfire.fc100.event.service.AgentFireNotificationTransactionParticipant;
import com.yx.uavfire.fc100.event.service.AgentFireReportIngress;
import com.yx.uavfire.fc100.event.service.AgentFireReportValidator;
import com.yx.uavfire.fc100.event.service.AgentFireTaskBindingResolver;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Ordered, immutable and transactional backend projection of Agent fire reports. */
@Service
public class PersistentAgentFireReportIngress implements AgentFireReportIngress {
    private static final int MAX_RAW_BYTES = 1024 * 1024;
    private static final Set<String> TERMINAL_LOCATIONS = set("PRECISE", "DEGRADED_OSD");
    private static final Map<String, Set<String>> NEXT = transitions();

    private final FireEventMapper events;
    private final FireEventHistoryMapper histories;
    private final AgentFireReportMapper reports;
    private final AgentFireNotificationTransactionParticipant notifications;
    private final Clock clock;
    private final ObjectMapper json;
    private final AgentFireTaskBindingResolver taskBindings;

    public PersistentAgentFireReportIngress(FireEventMapper events,
                                            FireEventHistoryMapper histories,
                                            AgentFireReportMapper reports,
                                            AgentFireNotificationTransactionParticipant notifications,
                                            Clock clock,
                                            ObjectMapper json,
                                            AgentFireTaskBindingResolver taskBindings) {
        this.events = events;
        this.histories = histories;
        this.reports = reports;
        this.notifications = notifications;
        this.clock = clock;
        this.json = json;
        this.taskBindings = taskBindings;
    }

    @Override
    @Transactional
    public Result accept(AgentFireReportParam report, byte[] rawPayload, String payloadSha256) {
        requireRawIdentity(rawPayload, payloadSha256);
        AgentFireReportEntity duplicate = reports.selectByEventAndSequence(report.getEventId(), report.getSequence());
        if (duplicate != null) return duplicateResult(duplicate, payloadSha256);

        FireEventEntity event = events.selectByEventId(report.getEventId());
        if (report.getSequence() == 1L) {
            if (!"VISUAL_CONFIRMED".equals(report.getState())) return Result.conflict("sequence 1 must be VISUAL_CONFIRMED");
            AgentFireTaskBindingResolver.Binding binding = taskBindings.resolveForInitialReport(
                report.getTaskId(), report.getDroneSn(), report.getEventTimestamp());
            if (binding == null) return Result.conflict("Agent report is not bound to an executing planned wayline");
            if (event == null) {
                try {
                    event = createInitialEvent(report, binding);
                    events.insert(event);
                } catch (DuplicateKeyException raced) {
                    event = events.selectByEventIdForUpdate(report.getEventId());
                }
            }
        }
        if (event == null) return Result.conflict("missing sequence 1");
        if (!sameIdentity(event, report)) return Result.conflict("immutable Agent identity mismatch");

        long previous = event.getLastAgentSequence() == null ? 0 : event.getLastAgentSequence();
        if (report.getSequence() != previous + 1) {
            AgentFireReportEntity raced = reports.selectByEventAndSequenceForUpdate(report.getEventId(), report.getSequence());
            return raced != null ? duplicateResult(raced, payloadSha256) : Result.conflict("sequence gap or out of order");
        }
        if (!allowedTransition(event.getDetectionStatus(), report.getState(), previous)) {
            return Result.conflict("invalid or regressive state transition");
        }
        if (TERMINAL_LOCATIONS.contains(event.getLocationStatus()) && "RESULT_DURABLE".equals(report.getState())) {
            return Result.conflict("terminal localization is immutable");
        }

        PrecisePoint precise = "PRECISE".equals(report.getLocationStatus()) ? validateAndMedian(report) : null;
        long now = clock.now();
        int notificationVersion = notificationVersion(event, report);
        boolean notificationRequired = notificationVersion > value(event.getNotificationVersion());

        int advanced = events.advanceAgentSequence(report.getEventId(), previous, report.getSequence(),
            report.getState(), report.getLocationStatus(), report.getFlightStatus(), now);
        if (advanced != 1) {
            AgentFireReportEntity raced = reports.selectByEventAndSequenceForUpdate(report.getEventId(), report.getSequence());
            return raced != null ? duplicateResult(raced, payloadSha256) : Result.conflict("concurrent sequence conflict");
        }

        applyProjection(event, report, precise, notificationVersion, now);
        boolean queued = false;
        if (notificationRequired) {
            queued = notifications.enqueue(event, report, notificationVersion);
            if (!queued) throw new AgentFireIngressUnavailableException("notification transaction participant did not persist");
        }
        events.updateById(event);
        insertHistory(event, report, notificationVersion, now);
        reports.insertImmutable(reportRow(event, report, rawPayload, payloadSha256,
            notificationVersion, queued, now));
        return Result.committed(queued);
    }

    private void requireRawIdentity(byte[] rawPayload, String expectedHash) {
        if (rawPayload == null || rawPayload.length == 0 || rawPayload.length > MAX_RAW_BYTES || expectedHash == null ||
            !expectedHash.equals(sha256(rawPayload))) throw new IllegalArgumentException("raw payload identity mismatch");
    }

    private Result duplicateResult(AgentFireReportEntity existing, String payloadSha256) {
        return existing.getPayloadSha256().equals(payloadSha256)
            ? Result.duplicate(Integer.valueOf(1).equals(existing.getNotificationQueued()))
            : Result.conflict("payload hash mismatch");
    }

    private FireEventEntity createInitialEvent(AgentFireReportParam p, AgentFireTaskBindingResolver.Binding binding) {
        long now = clock.now();
        FireEventEntity e = new FireEventEntity();
        e.setEventId(p.getEventId()); e.setWorkspaceId(binding.getWorkspaceId()); e.setSource("AGENT_VISIBLE");
        e.setDeviceSn(p.getDroneSn()); e.setConfidence(BigDecimal.valueOf(p.getConfidence()));
        e.setFireLevel("UNKNOWN"); e.setAltitudeReference("ELLIPSOID");
        e.setGeoQuality("LASER_LOCATING"); e.setEventTimestamp(p.getEventTimestamp());
        e.setLastSeenTime(p.getEventTimestamp()); e.setReportCount(1); e.setLastSourceEventId(p.getEventId());
        e.setNotificationVersion(0); e.setDetectionKind(p.getDetectionKind());
        e.setDetectionStatus(null); e.setLocationStatus(null); e.setFlightStatus(null);
        e.setAgentId(p.getAgentId()); e.setAgentSessionId(p.getSessionId()); e.setAgentTaskId(p.getTaskId());
        e.setLastAgentSequence(0L); e.setModelVersion(p.getModelVersion()); e.setModelHash(p.getModelHash());
        e.setPolicyVersion(p.getPolicyVersion()); e.setInputSize(p.getInputSize()); e.setRuntime(p.getRuntime());
        e.setSourceGeneration(p.getSourceGeneration()); e.setCoordinatorGeneration(p.getCoordinatorGeneration());
        e.setVisibleRoi(writeJson(p.getVisibleRoi())); e.setAgentSpatialMergeEligible(0);
        copyAircraft(e, p); e.setStatus("CANDIDATE"); e.setConfirmedStatus("PENDING"); e.setDeleted(0);
        e.setCreatedBy(p.getAgentId()); e.setUpdatedBy(p.getAgentId()); e.setCreateTime(now); e.setUpdateTime(now);
        return e;
    }

    private boolean sameIdentity(FireEventEntity e, AgentFireReportParam p) {
        return eq(e.getAgentId(), p.getAgentId()) && eq(e.getDeviceSn(), p.getDroneSn()) &&
            eq(e.getAgentTaskId(), p.getTaskId()) && eq(e.getAgentSessionId(), p.getSessionId()) &&
            eq(e.getDetectionKind(), p.getDetectionKind()) && eq(e.getModelVersion(), p.getModelVersion()) &&
            eq(e.getModelHash(), p.getModelHash()) && eq(e.getPolicyVersion(), p.getPolicyVersion()) &&
            eq(e.getInputSize(), p.getInputSize()) && eq(e.getRuntime(), p.getRuntime()) &&
            eq(e.getSourceGeneration(), p.getSourceGeneration()) &&
            eq(e.getCoordinatorGeneration(), p.getCoordinatorGeneration()) &&
            eq(e.getVisibleRoi(), writeJson(p.getVisibleRoi()));
    }

    private void applyProjection(FireEventEntity e, AgentFireReportParam p, PrecisePoint precise,
                                 int notificationVersion, long now) {
        e.setLastAgentSequence(p.getSequence()); e.setDetectionStatus(p.getState());
        if (p.getLocationStatus() != null) e.setLocationStatus(p.getLocationStatus());
        if (p.getFlightStatus() != null) e.setFlightStatus(p.getFlightStatus());
        e.setNotificationVersion(notificationVersion); e.setLastSeenTime(p.getEventTimestamp());
        e.setLastSourceEventId(p.getEventId()); e.setUpdatedBy(p.getAgentId()); e.setUpdateTime(now);
        if ("DEGRADED_OSD".equals(p.getLocationStatus())) {
            e.setLat(null); e.setLng(null); e.setAlt(null); copyAircraft(e, p);
            e.setGeoMethod("AIRCRAFT_OBSERVATION"); e.setGeoQuality("DEGRADED_OSD");
            e.setGeoErrorRadiusM(null); e.setGeoSourceTs(p.getEventTimestamp()); e.setAgentSpatialMergeEligible(0);
        } else if (precise != null) {
            e.setLat(precise.lat); e.setLng(precise.lng); e.setAlt(precise.alt); copyAircraft(e, p);
            e.setGeoMethod("LASER_RANGEFINDER"); e.setGeoQuality("PRECISE");
            e.setGeoErrorRadiusM(p.getErrorRadiusMeters()); e.setGeoSourceTs(p.getEventTimestamp());
            e.setAgentSpatialMergeEligible(1);
        }
    }

    private int notificationVersion(FireEventEntity event, AgentFireReportParam report) {
        int current = value(event.getNotificationVersion());
        if (report.getSequence() == 1L) return 1;
        if ("RESULT_DURABLE".equals(report.getState()) && !TERMINAL_LOCATIONS.contains(event.getLocationStatus()))
            return current + 1;
        return current;
    }

    private void insertHistory(FireEventEntity e, AgentFireReportParam p, int version, long now) {
        FireEventHistoryEntity h = new FireEventHistoryEntity();
        h.setFireEventId(e.getId()); h.setEventId(e.getEventId()); h.setSourceEventId(e.getEventId());
        h.setWorkspaceId(e.getWorkspaceId()); h.setSource(e.getSource()); h.setDeviceSn(e.getDeviceSn());
        h.setConfidence(e.getConfidence()); h.setFireLevel(e.getFireLevel()); h.setLat(e.getLat()); h.setLng(e.getLng());
        h.setAlt(e.getAlt()); h.setAltitudeReference(e.getAltitudeReference()); h.setGeoMethod(e.getGeoMethod());
        h.setGeoErrorRadiusM(e.getGeoErrorRadiusM()); h.setGeoQuality(e.getGeoQuality()); h.setGeoSourceTs(e.getGeoSourceTs());
        h.setAircraftLat(e.getAircraftLat()); h.setAircraftLng(e.getAircraftLng()); h.setAircraftAlt(e.getAircraftAlt());
        h.setEventTimestamp(p.getEventTimestamp()); h.setAction("AGENT_" + p.getState()); h.setAgentSequence(p.getSequence());
        h.setDetectionKind(p.getDetectionKind()); h.setDetectionStatus(p.getState()); h.setLocationStatus(e.getLocationStatus());
        h.setFlightStatus(e.getFlightStatus()); h.setAgentSessionId(p.getSessionId()); h.setNotificationVersion(version);
        h.setCreateTime(now); histories.insert(h);
    }

    private AgentFireReportEntity reportRow(FireEventEntity e, AgentFireReportParam p, byte[] raw, String hash,
                                             int version, boolean queued, long now) {
        AgentFireReportEntity r = new AgentFireReportEntity();
        r.setFireEventId(e.getId()); r.setEventId(p.getEventId()); r.setSequence(p.getSequence());
        r.setPayloadSha256(hash); r.setRawPayload(new String(raw, StandardCharsets.UTF_8));
        r.setAgentId(p.getAgentId()); r.setDroneSn(p.getDroneSn()); r.setTaskId(p.getTaskId());
        r.setAgentSessionId(p.getSessionId()); r.setState(p.getState()); r.setDetectionKind(p.getDetectionKind());
        r.setLocationStatus(p.getLocationStatus()); r.setFlightStatus(p.getFlightStatus());
        r.setModelVersion(p.getModelVersion()); r.setModelHash(p.getModelHash()); r.setPolicyVersion(p.getPolicyVersion());
        r.setInputSize(p.getInputSize()); r.setRuntime(p.getRuntime()); r.setSourceGeneration(p.getSourceGeneration());
        r.setCoordinatorGeneration(p.getCoordinatorGeneration()); r.setEventTimestamp(p.getEventTimestamp());
        r.setNotificationVersion(version); r.setNotificationQueued(queued ? 1 : 0); r.setStatus("ACCEPTED");
        r.setReceivedTime(now); return r;
    }

    private PrecisePoint validateAndMedian(AgentFireReportParam p) {
        if (p.getLaserSamples() == null || p.getLaserSamples().size() != 3 || p.getErrorRadiusMeters() == null ||
            !Double.isFinite(p.getErrorRadiusMeters()) || p.getErrorRadiusMeters() <= 0 || p.getErrorRadiusMeters() > 100 ||
            !finite(p.getFireLat(), -90, 90) || !finite(p.getFireLng(), -180, 180) ||
            !finite(p.getFireAlt(), -1000, 20000)) invalid("invalid precise samples");
        double[] lat = new double[3], lng = new double[3], alt = new double[3];
        for (int i = 0; i < 3; i++) {
            AgentFireReportParam.LaserSample s = p.getLaserSamples().get(i);
            if (s == null || !s.getUnknownFields().isEmpty() || !"NORMAL".equals(s.getStatus()) || !finite(s.getLat(), -90, 90) ||
                !finite(s.getLng(), -180, 180) || !finite(s.getAlt(), -1000, 20000) ||
                !finite(s.getRangeMeters(), 0.000001, 5000) || s.getEventTimestamp() == null ||
                s.getEventTimestamp() < p.getEventTimestamp() - AgentFireReportValidator.MAX_LASER_SAMPLE_SKEW_MILLIS ||
                s.getEventTimestamp() > p.getEventTimestamp() + AgentFireReportValidator.MAX_LASER_SAMPLE_SKEW_MILLIS)
                invalid("invalid precise sample");
            lat[i] = s.getLat(); lng[i] = s.getLng(); alt[i] = s.getAlt();
        }
        Arrays.sort(lat); Arrays.sort(lng); Arrays.sort(alt);
        PrecisePoint median = new PrecisePoint(lat[1], lng[1], alt[1]);
        for (AgentFireReportParam.LaserSample s : p.getLaserSamples()) {
            if (distance3d(median.lat, median.lng, median.alt, s.getLat(), s.getLng(), s.getAlt()) > p.getErrorRadiusMeters())
                invalid("laser sample scatter exceeds declared error");
        }
        if (distance3d(median.lat, median.lng, median.alt, p.getFireLat(), p.getFireLng(), p.getFireAlt()) >
            p.getErrorRadiusMeters()) invalid("client fire point disagrees with server median");
        return median;
    }

    private boolean allowedTransition(String from, String to, long previousSequence) {
        if (previousSequence == 0) return "VISUAL_CONFIRMED".equals(to);
        Set<String> allowed = NEXT.get(from);
        return allowed != null && allowed.contains(to);
    }

    private void copyAircraft(FireEventEntity e, AgentFireReportParam p) {
        if (p.getAircraft() != null) {
            e.setAircraftLat(p.getAircraft().getLat()); e.setAircraftLng(p.getAircraft().getLng());
            e.setAircraftAlt(p.getAircraft().getAlt());
        }
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("cannot serialize Agent identity", e); }
    }

    private String sha256(byte[] payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private double distance3d(double lat1, double lng1, double alt1, Double lat2, Double lng2, Double alt2) {
        if (lat2 == null || lng2 == null || alt2 == null) invalid("missing precise coordinate");
        double a1 = Math.toRadians(lat1), a2 = Math.toRadians(lat2);
        double dLat = a2 - a1, dLng = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(a1) * Math.cos(a2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double horizontal = 2 * 6_371_000 * Math.asin(Math.sqrt(Math.min(1, Math.max(0, h))));
        return Math.hypot(horizontal, alt2 - alt1);
    }

    private boolean finite(Double value, double min, double max) {
        return value != null && Double.isFinite(value) && value >= min && value <= max;
    }
    private int value(Integer v) { return v == null ? 0 : v; }
    private boolean eq(Object a, Object b) { return a == null ? b == null : a.equals(b); }
    private static void invalid(String message) { throw new IllegalArgumentException(message); }
    private static Set<String> set(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }
    private static Map<String, Set<String>> transitions() {
        Map<String, Set<String>> m = new HashMap<>();
        m.put("VISUAL_CONFIRMED", set("HOLD_REQUESTED", "MANUAL_HOLD"));
        m.put("HOLD_REQUESTED", set("HOVER_VERIFYING", "MANUAL_HOLD"));
        m.put("HOVER_VERIFYING", set("TARGET_ALIGNING", "MANUAL_HOLD"));
        m.put("TARGET_ALIGNING", set("LASER_MEASURING", "RESULT_DURABLE", "MANUAL_HOLD"));
        m.put("LASER_MEASURING", set("RESULT_DURABLE", "MANUAL_HOLD"));
        m.put("RESULT_DURABLE", set("RESUME_REQUESTED", "MANUAL_HOLD"));
        m.put("RESUME_REQUESTED", set("MISSION_RESUMED", "MANUAL_HOLD"));
        m.put("MISSION_RESUMED", set("SCANNING", "MANUAL_HOLD"));
        m.put("MANUAL_HOLD", set("SCANNING"));
        return Collections.unmodifiableMap(m);
    }
    private static final class PrecisePoint {
        final double lat, lng, alt;
        PrecisePoint(double lat, double lng, double alt) { this.lat = lat; this.lng = lng; this.alt = alt; }
    }
}
