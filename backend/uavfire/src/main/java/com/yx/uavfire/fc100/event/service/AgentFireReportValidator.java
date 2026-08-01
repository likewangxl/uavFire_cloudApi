package com.yx.uavfire.fc100.event.service;

import com.yx.uavfire.fc100.event.model.param.AgentFireReportParam;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Component
public class AgentFireReportValidator {
    /** Authenticated durable observations may replay after an outage for 30 days. */
    public static final long MAX_REPLAY_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000;
    public static final long MAX_FUTURE_CLOCK_SKEW_MILLIS = 5 * 60 * 1000L;
    static final long MAX_LASER_SAMPLE_SKEW_MILLIS = 5 * 60 * 1000L;
    static final String MODEL_VERSION = "visible-fire-wechat-best2-20260728";
    static final String MODEL_HASH = "957bec7a567ce1f57f9a57187a6b085c7c95149b889773479d018e3ed5e9f650";
    private static final Set<String> STATES = set("VISUAL_CONFIRMED", "HOLD_REQUESTED", "HOVER_VERIFYING",
        "TARGET_ALIGNING", "LASER_MEASURING", "RESULT_DURABLE", "RESUME_REQUESTED", "MISSION_RESUMED",
        "SCANNING", "MANUAL_HOLD");
    private static final Set<String> REASONS = set("PAUSE_TIMEOUT", "PAUSE_FAILED", "HOVER_TIMEOUT",
        "MANUAL_TAKEOVER", "LOW_BATTERY", "RETURN_TO_HOME", "OBSTACLE_AVOIDANCE", "FLIGHT_ERROR",
        "UNKNOWN_MISSION_STATE", "MISSING_BREAKPOINT", "BREAKPOINT_MISMATCH", "ROI_OR_LASER_FAILURE",
        "AIRCRAFT_OSD_UNAVAILABLE", "MANUAL_INTERVENTION", "STORAGE_FAILURE", "DETECTOR_FAILURE",
        "LASER_DISABLE_UNCERTAIN", "RESUME_FAILURE", "CANCELLED_AFTER_FLIGHT_SUBMISSION",
        "CANCELLED_AFTER_DURABLE_HOLD_INTENT", "STARTUP_RECOVERY_UNCERTAIN", "STALE_SESSION_EVIDENCE",
        "STARTUP_FLIGHT_STATE_UNRECONCILED");
    private static final Set<String> DEGRADED_REASONS = set("TARGET_NOT_ALIGNED", "TARGET_DETECTION_TIMEOUT",
        "LASER_ENABLE_FAILED", "LASER_CALLBACK_OVERFLOW", "LASER_SAMPLES_UNAVAILABLE", "LASER_SAMPLES_INVALID");
    private static final Map<String, String> FLIGHT = flightMap();
    private final Clock clock;

    public AgentFireReportValidator() { this(Clock.systemUTC()); }
    AgentFireReportValidator(Clock clock) { this.clock = clock; }

    public void validate(AgentFireReportParam p) {
        required(p, "report");
        if (!p.getUnknownFields().isEmpty()) fail("unknown report fields are not allowed");
        text(p.getAgentId(), "agentId"); text(p.getDroneSn(), "droneSn"); text(p.getTaskId(), "taskId");
        text(p.getEventId(), "eventId"); text(p.getSessionId(), "sessionId");
        if (p.getSequence() == null || p.getSequence() <= 0) fail("sequence must be positive");
        long now = clock.millis();
        if (p.getEventTimestamp() == null || p.getEventTimestamp() < now - MAX_REPLAY_AGE_MILLIS)
            fail("eventTimestamp exceeds authenticated replay retention");
        if (p.getEventTimestamp() > now + MAX_FUTURE_CLOCK_SKEW_MILLIS)
            fail("eventTimestamp exceeds future clock skew policy");
        if (!STATES.contains(p.getState())) fail("unknown state");
        if (!set("FIRE", "SMOKE").contains(p.getDetectionKind())) fail("unknown detectionKind");
        if (!MODEL_VERSION.equals(p.getModelVersion()) || !MODEL_HASH.equals(p.getModelHash()) ||
            !"agent-visible-v1".equals(p.getPolicyVersion()) || !Integer.valueOf(960).equals(p.getInputSize()) ||
            !"NCNN".equals(p.getRuntime())) fail("model release identity is not allowed");
        if (p.getSourceGeneration() == null || p.getSourceGeneration() <= 0 ||
            p.getCoordinatorGeneration() == null || p.getCoordinatorGeneration() <= 0) fail("generation must be positive");
        roi(p.getVisibleRoi());

        if ("RESULT_DURABLE".equals(p.getState())) terminal(p);
        else nonTerminal(p);
    }

    private void nonTerminal(AgentFireReportParam p) {
        if (any(p.getFireLat(), p.getFireLng(), p.getFireAlt()) || p.getLaserSamples() != null ||
            p.getDegradedReason() != null || p.getGeoMethod() != null || p.getErrorRadiusMeters() != null)
            fail("terminal location fields are not allowed");
        if (p.getAircraft() != null) point(p.getAircraft(), "aircraft");
        if ("VISUAL_CONFIRMED".equals(p.getState())) {
            finiteRange(p.getConfidence(), 0, 1, "confidence");
            if (!"LASER_LOCATING".equals(p.getLocationStatus()) || p.getFlightStatus() != null) fail("invalid initial status");
        } else {
            if (p.getConfidence() != null || p.getAircraft() != null) fail("initial-only fields are not allowed");
            String expected = FLIGHT.get(p.getState());
            if (expected == null || !expected.equals(p.getFlightStatus())) fail("invalid flightStatus");
            if (set("HOLD_REQUESTED", "HOVER_VERIFYING", "TARGET_ALIGNING", "LASER_MEASURING").contains(p.getState()) &&
                !"LASER_LOCATING".equals(p.getLocationStatus())) fail("invalid locating status");
            if (!set("HOLD_REQUESTED", "HOVER_VERIFYING", "TARGET_ALIGNING", "LASER_MEASURING").contains(p.getState()) &&
                p.getLocationStatus() != null) fail("locationStatus is incompatible with state");
            if ("MANUAL_HOLD".equals(p.getState())) {
                if (!REASONS.contains(p.getReason())) fail("manual hold requires typed reason");
            } else if (p.getReason() != null) fail("reason is only allowed for manual hold");
        }
    }

    private void terminal(AgentFireReportParam p) {
        if (p.getFlightStatus() != null || p.getReason() != null || p.getConfidence() != null)
            fail("terminal state has incompatible status");
        if ("PRECISE".equals(p.getLocationStatus())) {
            if (!"LASER_RANGEFINDER".equals(p.getGeoMethod()) || p.getDegradedReason() != null)
                fail("invalid precise mapping");
            if (p.getAircraft() != null) point(p.getAircraft(), "aircraft");
            point(p.getFireLat(), p.getFireLng(), p.getFireAlt(), "fire");
            finiteRange(p.getErrorRadiusMeters(), 0.000001, 100, "errorRadiusMeters");
            if (p.getLaserSamples() == null || p.getLaserSamples().size() != 3) fail("precise requires three laser samples");
            for (AgentFireReportParam.LaserSample s : p.getLaserSamples()) {
                if (s == null || !"NORMAL".equals(s.getStatus())) fail("invalid laser status");
                if (!s.getUnknownFields().isEmpty()) fail("unknown laser sample fields are not allowed");
                finiteRange(s.getRangeMeters(), 0.000001, 5000, "laser range");
                point(s.getLat(), s.getLng(), s.getAlt(), "laser sample");
                if (horizontalDistanceMeters(p.getFireLat(), p.getFireLng(), s.getLat(), s.getLng()) >
                    p.getErrorRadiusMeters()) fail("laser sample scatter exceeds error radius");
                if (s.getEventTimestamp() == null ||
                    s.getEventTimestamp() < p.getEventTimestamp() - MAX_LASER_SAMPLE_SKEW_MILLIS ||
                    s.getEventTimestamp() > p.getEventTimestamp() + MAX_LASER_SAMPLE_SKEW_MILLIS)
                    fail("invalid laser timestamp");
            }
        } else if ("DEGRADED_OSD".equals(p.getLocationStatus())) {
            if (!"AIRCRAFT_OBSERVATION".equals(p.getGeoMethod()) || any(p.getFireLat(), p.getFireLng(), p.getFireAlt()) ||
                p.getLaserSamples() != null || p.getErrorRadiusMeters() != null || !DEGRADED_REASONS.contains(p.getDegradedReason()))
                fail("invalid degraded mapping");
            if (p.getAircraft() == null) fail("degraded report requires aircraft coordinates");
            point(p.getAircraft(), "aircraft");
        } else fail("unknown terminal locationStatus");
    }

    private void roi(AgentFireReportParam.Roi r) {
        if (r == null) fail("visibleRoi is required");
        if (!r.getUnknownFields().isEmpty()) fail("unknown ROI fields are not allowed");
        finiteRange(r.getX(), 0, 1, "roi.x"); finiteRange(r.getY(), 0, 1, "roi.y");
        finiteRange(r.getWidth(), Double.MIN_VALUE, 1, "roi.width");
        finiteRange(r.getHeight(), Double.MIN_VALUE, 1, "roi.height");
        if (r.getX() + r.getWidth() > 1 || r.getY() + r.getHeight() > 1) fail("ROI exceeds normalized bounds");
    }

    private void point(Double lat, Double lng, Double alt, String name) {
        finiteRange(lat, -90, 90, name + ".lat"); finiteRange(lng, -180, 180, name + ".lng");
        finiteRange(alt, -1000, 20000, name + ".alt");
    }
    private void point(AgentFireReportParam.Point p, String name) {
        if (!p.getUnknownFields().isEmpty()) fail("unknown " + name + " fields are not allowed");
        point(p.getLat(), p.getLng(), p.getAlt(), name);
    }
    private void finiteRange(Double v, double min, double max, String name) {
        if (v == null || !Double.isFinite(v) || v < min || v > max) fail(name + " is invalid");
    }
    private double horizontalDistanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double a1 = Math.toRadians(lat1);
        double a2 = Math.toRadians(lat2);
        double dLat = a2 - a1;
        double dLng = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(a1) * Math.cos(a2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * 6_371_000 * Math.asin(Math.sqrt(Math.min(1, Math.max(0, h))));
    }
    private void text(String v, String name) { if (v == null || v.trim().isEmpty() || v.length() > 128) fail(name + " is invalid"); }
    private void required(Object v, String name) { if (v == null) fail(name + " is required"); }
    private boolean any(Object... values) { for (Object v : values) if (v != null) return true; return false; }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
    private static Set<String> set(String... v) { return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(v))); }
    private static Map<String, String> flightMap() {
        Map<String, String> m = new HashMap<>();
        m.put("HOLD_REQUESTED", "HOLD_REQUESTED"); m.put("HOVER_VERIFYING", "HOVERING");
        m.put("TARGET_ALIGNING", "TARGET_ALIGNING"); m.put("LASER_MEASURING", "LASER_MEASURING");
        m.put("RESUME_REQUESTED", "RESUME_REQUESTED"); m.put("MISSION_RESUMED", "MISSION_RESUMED");
        m.put("SCANNING", "SCANNING"); m.put("MANUAL_HOLD", "MANUAL_HOLD"); return Collections.unmodifiableMap(m);
    }
}
