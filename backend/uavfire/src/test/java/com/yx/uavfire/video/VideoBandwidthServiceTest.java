package com.yx.uavfire.video;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class VideoBandwidthServiceTest {
    private final AtomicLong now = new AtomicLong(1_000);
    private final VideoBandwidthProperties props = properties();
    private final VideoBandwidthService service = new VideoBandwidthService(props, now::get);

    private static VideoBandwidthProperties properties() {
        VideoBandwidthProperties p = new VideoBandwidthProperties();
        p.setEnabled(true);
        p.setAircraftSns(IntStream.range(0, 20).mapToObj(i -> String.format("DRONE-%02d", i)).collect(Collectors.toList()));
        return p;
    }
    private VideoPolicyReport report(String sn) {
        VideoPolicyReport r = new VideoPolicyReport();
        r.setProtocolVersion(1); r.setInstanceId("instance-" + sn);
        r.setStreaming(true); r.setAppliedProfile("LOW"); r.setConfiguredBitrateBps(500_000);
        return r;
    }
    private List<VideoPolicyDecision> pollAll() {
        return props.getAircraftSns().stream().map(sn -> service.report(sn, report(sn))).collect(Collectors.toList());
    }
    private void ready() { pollAll(); now.addAndGet(25_001); pollAll(); }
    private long highCount(List<VideoPolicyDecision> decisions) {
        return decisions.stream().filter(d -> d.getProfile().equals("HIGH")).count();
    }

    @Test void startupIsLowThenTwentyAircraftHaveFourHighSixteenLow() {
        assertEquals(0, highCount(pollAll()));
        now.addAndGet(25_001);
        List<VideoPolicyDecision> decisions = pollAll();
        assertEquals(4, highCount(decisions));
        assertEquals(24_000_000, decisions.stream().mapToInt(VideoPolicyDecision::getBitrateBps).sum());
    }

    @Test void concurrentRequestsCannotOverAllocate() throws Exception {
        now.addAndGet(25_001);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<Callable<VideoPolicyDecision>> work = props.getAircraftSns().stream()
                    .map(sn -> (Callable<VideoPolicyDecision>) () -> service.report(sn, report(sn))).collect(Collectors.toList());
            List<VideoPolicyDecision> result = new ArrayList<>();
            for (Future<VideoPolicyDecision> future : pool.invokeAll(work)) result.add(future.get());
            assertEquals(4, highCount(result));
        } finally { pool.shutdownNow(); }
    }

    @Test void handoverWaitsForLeaseAndDeviceDrainEvenAfterLowReport() {
        ready();
        service.view("user", "page-001", "DRONE-19");
        assertEquals("waiting-for-high-slot", service.report("DRONE-19", report("DRONE-19")).getReason());
        assertEquals("LOW", service.report("DRONE-03", report("DRONE-03")).getProfile());
        now.addAndGet(15_001);
        service.view("user", "page-001", "DRONE-19");
        assertEquals("LOW", service.report("DRONE-19", report("DRONE-19")).getProfile());
        now.addAndGet(10_000);
        assertEquals("HIGH", service.report("DRONE-19", report("DRONE-19")).getProfile());
    }

    @Test void restartDrainsOutstandingLeasesAndOfflineDoesNotFreeThemEarly() {
        ready();
        now.addAndGet(15_001);
        assertEquals("LOW", service.report("DRONE-19", report("DRONE-19")).getProfile());
        VideoBandwidthService restarted = new VideoBandwidthService(props, now::get);
        assertEquals("coordinator-startup-drain", restarted.report("DRONE-19", report("DRONE-19")).getReason());
        now.addAndGet(25_001);
        assertEquals("HIGH", restarted.report("DRONE-19", report("DRONE-19")).getProfile());
    }

    @Test void duplicateInstanceCannotRenewOrReceiveAnotherHigh() {
        ready();
        VideoPolicyReport other = report("DRONE-00"); other.setInstanceId("second-instance");
        assertEquals("duplicate-agent-instance", service.report("DRONE-00", other).getReason());
        assertEquals("duplicate-agent-instance", service.report("DRONE-00", report("DRONE-00")).getReason());
        now.addAndGet(25_001);
        assertEquals("HIGH", service.report("DRONE-00", other).getProfile());
    }

    @Test void disabledUnknownAndIncompatibleNeverGetHigh() {
        ready(); props.setEnabled(false);
        assertEquals("allocation-disabled", service.report("DRONE-00", report("DRONE-00")).getReason());
        assertEquals("aircraft-not-enrolled", service.report("OUTSIDE", report("OUTSIDE")).getReason());
        VideoPolicyReport invalid = report("DRONE-01"); invalid.setProtocolVersion(0);
        assertEquals("protocol-unsupported", service.report("DRONE-01", invalid).getReason());
    }

    @Test void verifiedFirePreemptsViewerWithoutReleasingExistingSlot() {
        ready();
        for (int i = 0; i < 4; i++) service.view("user", "page-" + i, String.format("DRONE-%02d", i));
        service.prioritizeVerifiedFire("DRONE-19");
        assertEquals("LOW", service.report("DRONE-03", report("DRONE-03")).getProfile());
        assertEquals("waiting-for-high-slot", service.report("DRONE-19", report("DRONE-19")).getReason());
    }

    @Test void viewerExpiryAndIndependentOwnersAreBounded() {
        ready();
        service.view("one", "page-001", "DRONE-19");
        service.view("two", "page-001", "DRONE-19");
        service.view("one", "page-001", null);
        assertEquals("waiting-for-high-slot", service.report("DRONE-19", report("DRONE-19")).getReason());
        now.addAndGet(20_001);
        pollAll();
        assertEquals("LOW", service.report("DRONE-19", report("DRONE-19")).getProfile());
        for (int i = 0; i < 16; i++) service.view("one", "page-" + i, "DRONE-19");
        assertThrows(IllegalArgumentException.class, () -> service.view("one", "page-overflow", "DRONE-19"));
    }

    @Test void missingAndStaleTelemetryRemainUnknownAndStatusIsFiltered() {
        Map<String, Object> status = service.status(Set.of("DRONE-01"));
        List<?> rows = (List<?>) status.get("aircraft");
        assertEquals(1, rows.size());
        assertNull(((Map<?, ?>) rows.get(0)).get("agent"));
    }

    @Test void lowDecisionAndOutstandingHighReservationAreReportedSeparately() {
        ready();
        VideoPolicyReport fault = report("DRONE-01"); fault.setError("encoder-fault");
        assertEquals("LOW", service.report("DRONE-01", fault).getProfile());
        List<?> rows = (List<?>) service.status(Set.of("DRONE-01")).get("aircraft");
        Map<?, ?> row = (Map<?, ?>) rows.get(0);
        assertEquals("LOW", row.get("target_profile"));
        assertEquals(true, row.get("outstanding_high"));
        assertEquals(true, row.get("reserved"));
    }
}
