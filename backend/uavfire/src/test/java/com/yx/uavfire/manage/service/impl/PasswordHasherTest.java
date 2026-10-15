package com.yx.uavfire.manage.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashedPasswordMatchesAndPlaintextStillMatches() {
        String stored = hasher.hash("adminPC");
        assertTrue(stored.startsWith("{bcrypt}"));
        assertTrue(hasher.isHashed(stored));
        assertTrue(hasher.matches("adminPC", stored));
        assertFalse(hasher.matches("wrong", stored));
        assertTrue(hasher.matches("legacy", "legacy"));
        assertFalse(hasher.matches("legacy", "other"));
        assertFalse(hasher.matches(null, stored));
    }
}
