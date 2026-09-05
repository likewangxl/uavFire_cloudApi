package com.yx.uavfire.trial;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrialExpirationPolicyTest {

    @Test
    void remainsUsableImmediatelyBeforeCutoff() {
        assertFalse(policyAt("2026-09-30T15:59:59.999Z").isExpired());
    }

    @Test
    void expiresAtBeijingMidnightOnOctoberFirst() {
        assertTrue(policyAt("2026-09-30T16:00:00Z").isExpired());
        assertTrue(policyAt("2026-10-01T00:00:00Z").isExpired());
    }

    private TrialExpirationPolicy policyAt(String instant) {
        return new TrialExpirationPolicy(
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }
}
