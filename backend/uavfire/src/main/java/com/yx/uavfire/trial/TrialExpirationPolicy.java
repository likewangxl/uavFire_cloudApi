package com.yx.uavfire.trial;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Fixed trial-license boundary shared by backend startup and request guards. */
public final class TrialExpirationPolicy {

    public static final Instant EXPIRES_AT = Instant.parse("2026-09-30T16:00:00Z");
    public static final String EXPIRES_AT_DISPLAY = "2026-10-01 00:00:00 Asia/Shanghai";
    public static final String EXPIRED_MESSAGE = "试用版本已于 2026 年 10 月 1 日 00:00（北京时间）到期";

    private final Clock clock;

    public TrialExpirationPolicy(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static TrialExpirationPolicy systemClock() {
        return new TrialExpirationPolicy(Clock.systemUTC());
    }

    public boolean isExpired() {
        return !clock.instant().isBefore(EXPIRES_AT);
    }

    public Duration remaining() {
        Duration remaining = Duration.between(clock.instant(), EXPIRES_AT);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }
}
