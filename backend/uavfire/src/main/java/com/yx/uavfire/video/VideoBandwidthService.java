package com.yx.uavfire.video;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/** One coordinator per bandwidth domain. Deadlines use a monotonic clock. */
@Service
public class VideoBandwidthService {
    public static final int HIGH_BPS = 4_000_000;
    public static final int LOW_BPS = 500_000;
    public static final int MAX_HIGH = 4;
    public static final long LEASE_MS = 15_000;
    public static final long DRAIN_GUARD_MS = 10_000;
    public static final long ONLINE_MS = 15_000;
    public static final long VIEWER_MS = 20_000;
    private final VideoBandwidthProperties properties;
    private final Set<String> fleet;
    private final LongSupplier clock;
    private final long startupHoldUntil;
    private final Map<String, AgentState> agents = new HashMap<>();
    private final Map<String, Reservation> reservations = new HashMap<>();
    private final Map<String, Viewer> viewers = new HashMap<>();
    private final Map<String, Long> firePriorityUntil = new HashMap<>();

    @Autowired
    public VideoBandwidthService(VideoBandwidthProperties properties) {
        this(properties, () -> System.nanoTime() / 1_000_000);
    }

    VideoBandwidthService(VideoBandwidthProperties properties, LongSupplier clock) {
        properties.validate();
        this.properties = properties;
        this.fleet = Collections.unmodifiableSet(new LinkedHashSet<>(properties.getAircraftSns()));
        this.clock = clock;
        this.startupHoldUntil = clock.getAsLong() + LEASE_MS + DRAIN_GUARD_MS;
    }

    public Set<String> fleet() { return fleet; }

    public synchronized VideoPolicyDecision report(String sn, VideoPolicyReport report) {
        long now = clock.getAsLong();
        cleanup(now);
        if (report == null || !Integer.valueOf(1).equals(report.getProtocolVersion())
                || report.getInstanceId() == null || !report.getInstanceId().matches("[A-Za-z0-9_-]{8,80}")) {
            return low(sn, report == null ? "" : report.getInstanceId(), "protocol-unsupported");
        }
        if (!fleet.contains(sn)) return low(sn, report.getInstanceId(), "aircraft-not-enrolled");
        AgentState state = agents.get(sn);
        if (state == null) {
            state = new AgentState(report.getInstanceId());
            agents.put(sn, state);
        } else if (!state.instanceId.equals(report.getInstanceId())) {
            if (now - state.lastSeen <= LEASE_MS + DRAIN_GUARD_MS) {
                state.conflictUntil = now + LEASE_MS + DRAIN_GUARD_MS;
                return low(sn, report.getInstanceId(), "duplicate-agent-instance");
            }
            // Old instance and all of its outstanding grants have drained.
            state = new AgentState(report.getInstanceId());
            agents.put(sn, state);
        }
        state.lastSeen = now;
        state.report = report;
        if (!properties.isEnabled()) return decideLow(sn, state, "allocation-disabled");
        if (now < startupHoldUntil) return decideLow(sn, state, "coordinator-startup-drain");
        if (state.conflictUntil > now) return decideLow(sn, state, "duplicate-agent-instance");
        if (!Boolean.TRUE.equals(report.getStreaming()) || hasError(report)) {
            return decideLow(sn, state, "stream-not-ready");
        }
        Set<String> desired = desiredHigh(now);
        if (!desired.contains(sn)) return decideLow(sn, state, "low-priority");
        Reservation reservation = reservations.get(sn);
        if (reservation != null && !reservation.instanceId.equals(state.instanceId)) {
            return decideLow(sn, state, "previous-instance-draining");
        }
        if (reservation == null && reservations.size() >= MAX_HIGH) {
            return decideLow(sn, state, "waiting-for-high-slot");
        }
        if (reservation == null) {
            reservation = new Reservation(state.instanceId);
            reservations.put(sn, reservation);
        }
        reservation.expiresAt = now + LEASE_MS;
        reservation.holdUntil = reservation.expiresAt + DRAIN_GUARD_MS;
        state.reason = "high-granted";
        state.targetProfile = "HIGH";
        return new VideoPolicyDecision(1, sn, state.instanceId, "HIGH", HIGH_BPS, LEASE_MS,
                reservation.leaseId, state.reason);
    }

    public synchronized void view(String owner, String viewerId, String sn) {
        long now = clock.getAsLong();
        cleanup(now);
        String key = owner + ":" + viewerId;
        if (sn == null || sn.isBlank()) { viewers.remove(key); return; }
        if (!fleet.contains(sn)) throw new IllegalArgumentException("aircraft-not-enrolled");
        // Bound memory for abandoned or malicious client-generated page identifiers.
        long ownerCount = viewers.values().stream().filter(v -> v.owner.equals(owner)).count();
        if (!viewers.containsKey(key) && (ownerCount >= 16 || viewers.size() >= 1024)) {
            throw new IllegalArgumentException("viewer-limit-reached");
        }
        viewers.put(key, new Viewer(owner, sn, now + VIEWER_MS));
    }

    public synchronized void prioritizeVerifiedFire(String sn) {
        if (fleet.contains(sn)) firePriorityUntil.put(sn, clock.getAsLong() + 60_000);
    }

    public synchronized Map<String, Object> status(Set<String> visibleSns) {
        long now = clock.getAsLong();
        cleanup(now);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String sn : fleet) {
            if (!visibleSns.contains(sn)) continue;
            AgentState a = agents.get(sn);
            Reservation r = reservations.get(sn);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("drone_sn", sn);
            row.put("online", a != null && now - a.lastSeen <= ONLINE_MS);
            row.put("report_age_ms", a == null ? null : Math.max(0, now - a.lastSeen));
            row.put("target_profile", a != null && r != null && r.expiresAt > now ? a.targetProfile : "LOW");
            row.put("outstanding_high", r != null && r.expiresAt > now);
            row.put("reserved", r != null);
            row.put("lease_remaining_ms", r == null ? 0 : Math.max(0, r.expiresAt - now));
            row.put("reason", a == null ? "awaiting-agent" : a.reason);
            row.put("agent", a == null ? null : a.report);
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", properties.isEnabled());
        result.put("max_high", MAX_HIGH);
        result.put("high_bitrate_bps", HIGH_BPS);
        result.put("low_bitrate_bps", LOW_BPS);
        result.put("aircraft", rows);
        return result;
    }

    private Set<String> desiredHigh(long now) {
        return agents.entrySet().stream()
                .filter(e -> now - e.getValue().lastSeen <= ONLINE_MS
                        && e.getValue().conflictUntil <= now && e.getValue().report != null
                        && Boolean.TRUE.equals(e.getValue().report.getStreaming())
                        && !hasError(e.getValue().report))
                .sorted(Comparator.<Map.Entry<String, AgentState>>comparingInt(e -> priority(e.getKey(), now)).reversed()
                        .thenComparing(e -> reservations.containsKey(e.getKey()) ? 0 : 1)
                        .thenComparing(Map.Entry::getKey))
                .limit(MAX_HIGH).map(Map.Entry::getKey).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private int priority(String sn, long now) {
        if (firePriorityUntil.getOrDefault(sn, 0L) > now) return 300;
        if (viewers.values().stream().anyMatch(v -> v.sn.equals(sn))) return 200;
        return properties.getPreferredAircraftSns().contains(sn) ? 100 : 0;
    }

    private void cleanup(long now) {
        reservations.entrySet().removeIf(e -> e.getValue().holdUntil <= now);
        viewers.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
        firePriorityUntil.entrySet().removeIf(e -> e.getValue() <= now);
    }

    private static boolean hasError(VideoPolicyReport report) {
        return report.getError() != null && !report.getError().isBlank();
    }

    private VideoPolicyDecision decideLow(String sn, AgentState state, String reason) {
        state.reason = reason;
        state.targetProfile = "LOW";
        return low(sn, state.instanceId, reason);
    }

    private VideoPolicyDecision low(String sn, String instance, String reason) {
        return new VideoPolicyDecision(1, sn, instance, "LOW", LOW_BPS, 0, "", reason);
    }

    private static final class AgentState {
        final String instanceId;
        long lastSeen;
        long conflictUntil;
        String reason = "awaiting-policy";
        String targetProfile = "LOW";
        VideoPolicyReport report;
        AgentState(String instanceId) { this.instanceId = instanceId; }
    }
    private static final class Reservation {
        final String instanceId;
        final String leaseId = UUID.randomUUID().toString();
        long expiresAt;
        long holdUntil;
        Reservation(String instanceId) { this.instanceId = instanceId; }
    }
    private static final class Viewer {
        final String owner;
        final String sn;
        final long expiresAt;
        Viewer(String owner, String sn, long expiresAt) {
            this.owner = owner; this.sn = sn; this.expiresAt = expiresAt;
        }
    }
}
