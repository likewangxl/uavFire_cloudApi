package com.yx.uavfire.trial;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrialLicensePolicyTest {

    private static final Clock AFTER_HISTORICAL_CUTOFF =
            Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void offDoesNotExpireAndLetsDevStart() {
        TrialExpirationPolicy policy = TrialExpirationPolicy.from(
                new TrialLicenseProperties(), AFTER_HISTORICAL_CUTOFF, false);

        assertEquals(TrialExpirationPolicy.Mode.OFF, policy.getMode());
        assertFalse(policy.isExpired());
        policy.assertCanStart();
        assertFalse(policy.schedulesShutdown());
    }

    @Test
    void productionOffWithoutExplicitOptInRefusesToStart() {
        TrialExpirationPolicy policy = TrialExpirationPolicy.from(
                new TrialLicenseProperties(), AFTER_HISTORICAL_CUTOFF, true);

        IllegalStateException error = assertThrows(IllegalStateException.class, policy::assertCanStart);
        assertTrue(error.getMessage().contains("UAVFIRE_TRIAL_ALLOW_UNLIMITED"));
    }

    @Test
    void productionOffStartsWhenUnlimitedIsExplicit() {
        TrialLicenseProperties properties = new TrialLicenseProperties();
        properties.setAllowUnlimited(true);
        TrialExpirationPolicy policy = TrialExpirationPolicy.from(properties, AFTER_HISTORICAL_CUTOFF, true);

        policy.assertCanStart();
        assertTrue(policy.display().contains("显式关闭试用截止"));
    }

    @Test
    void untilKeepsHistoricalCutoffWhenDateIsOmitted() {
        TrialLicenseProperties properties = new TrialLicenseProperties();
        properties.setMode("until");
        properties.setExpiresAt("  ");
        TrialExpirationPolicy policy = TrialExpirationPolicy.from(properties, AFTER_HISTORICAL_CUTOFF, false);

        assertEquals(TrialExpirationPolicy.EXPIRES_AT, policy.getExpiresAt());
        assertTrue(policy.isExpired());
        assertThrows(IllegalStateException.class, policy::assertCanStart);
    }

    @Test
    void untilWithFutureDateDoesNotExpire() {
        TrialLicenseProperties properties = new TrialLicenseProperties();
        properties.setMode("until");
        properties.setExpiresAt("2027-01-01T00:00:00+08:00");
        TrialExpirationPolicy policy = TrialExpirationPolicy.from(properties, AFTER_HISTORICAL_CUTOFF, true);

        assertFalse(policy.isExpired());
        policy.assertCanStart();
        assertTrue(policy.schedulesShutdown());
    }
}
