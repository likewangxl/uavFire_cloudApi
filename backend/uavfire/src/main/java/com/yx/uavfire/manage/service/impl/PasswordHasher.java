package com.yx.uavfire.manage.service.impl;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Stores passwords as {@code {bcrypt}<hash>} and still accepts legacy plaintext. */
public final class PasswordHasher {

    static final String PREFIX = "{bcrypt}";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public boolean matches(String rawPassword, String stored) {
        if (stored == null || rawPassword == null) {
            return false;
        }
        if (isHashed(stored)) {
            return encoder.matches(rawPassword, stored.substring(PREFIX.length()));
        }
        return rawPassword.equals(stored);
    }

    public boolean isHashed(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    public String hash(String rawPassword) {
        return PREFIX + encoder.encode(rawPassword);
    }
}
