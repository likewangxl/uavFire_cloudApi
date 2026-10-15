package com.yx.uavfire.common.util;

import com.auth0.jwt.algorithms.Algorithm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtSecretGuardTest {

    private Algorithm original;

    @BeforeEach
    void saveAlgorithm() {
        original = JwtUtil.algorithm;
    }

    @AfterEach
    void restoreAlgorithm() {
        JwtUtil.algorithm = original;
    }

    @Test
    void productionRejectsBlankAndSampleSecrets() {
        assertThrows(IllegalStateException.class,
                () -> new JwtSecretGuard(production(), "", false));
        assertThrows(IllegalStateException.class,
                () -> new JwtSecretGuard(production(), JwtSecretGuard.SAMPLE_SECRET, true));
    }

    @Test
    void sampleSecretIsRejectedUnlessExplicitlyAllowed() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new JwtSecretGuard(dev(), JwtSecretGuard.SAMPLE_SECRET, false));
        assertTrue(error.getMessage().contains("CloudApiSample"));

        new JwtSecretGuard(dev(), JwtSecretGuard.SAMPLE_SECRET, true);
        assertNotNull(JwtUtil.algorithm);
    }

    @Test
    void blankSecretOutsideProductionUsesAnEphemeralKey() {
        new JwtSecretGuard(dev(), "  ", false);
        assertNotNull(JwtUtil.algorithm);
        assertNotNull(JwtUtil.createToken(java.util.Map.of("id", "u1")));
    }

    @Test
    void configuredSecretIsApplied() {
        new JwtSecretGuard(dev(), "unit-test-secret-value", false);
        assertNotNull(JwtUtil.createToken(java.util.Map.of("id", "u1")));
    }

    private static Environment production() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"production"});
        return environment;
    }

    private static Environment dev() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        when(environment.getProperty("uavfire.env")).thenReturn("");
        when(environment.getProperty("UAVFIRE_ENV")).thenReturn(null);
        return environment;
    }
}
