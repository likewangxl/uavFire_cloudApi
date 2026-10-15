package com.yx.uavfire.trial;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Trial license. {@code until} keeps a real cutoff (historical default 2026-10-01 00:00 Asia/Shanghai).
 * {@code off} does not expire, and production must opt in with {@code allowUnlimited}.
 */
public final class TrialExpirationPolicy {

    public enum Mode {
        OFF,
        UNTIL
    }

    public static final Instant EXPIRES_AT = Instant.parse("2026-09-30T16:00:00Z");
    public static final String EXPIRES_AT_DISPLAY = "2026-10-01 00:00:00 Asia/Shanghai";
    public static final String EXPIRED_MESSAGE = "试用版本已于 2026 年 10 月 1 日 00:00（北京时间）到期";

    private final Mode mode;
    private final Instant expiresAt;
    private final Clock clock;
    private final boolean production;
    private final boolean allowUnlimited;

    /** Historical fixed cutoff. Kept so existing tests still describe that boundary. */
    public TrialExpirationPolicy(Clock clock) {
        this(Mode.UNTIL, EXPIRES_AT, clock, false, false);
    }

    public TrialExpirationPolicy(Mode mode, Instant expiresAt, Clock clock,
                                 boolean production, boolean allowUnlimited) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.expiresAt = expiresAt;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.production = production;
        this.allowUnlimited = allowUnlimited;
        if (mode == Mode.UNTIL && expiresAt == null) {
            throw new IllegalArgumentException("uavfire.trial.mode=until 需要 expires-at");
        }
    }

    public static TrialExpirationPolicy systemClock() {
        return new TrialExpirationPolicy(Clock.systemUTC());
    }

    public static TrialExpirationPolicy from(TrialLicenseProperties properties, Clock clock, boolean production) {
        TrialLicenseProperties source = properties == null ? new TrialLicenseProperties() : properties;
        Mode mode = parseMode(source.getMode());
        Instant expiresAt = mode == Mode.UNTIL ? parseExpiresAt(source.getExpiresAt()) : null;
        return new TrialExpirationPolicy(mode, expiresAt, clock, production, source.isAllowUnlimited());
    }

    public Mode getMode() {
        return mode;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isProduction() {
        return production;
    }

    public boolean isAllowUnlimited() {
        return allowUnlimited;
    }

    public void assertCanStart() {
        if (production && mode == Mode.OFF && !allowUnlimited) {
            throw new IllegalStateException(
                    "生产环境必须配置试用授权：UAVFIRE_TRIAL_MODE=until 且 UAVFIRE_TRIAL_EXPIRES_AT=到期时间，"
                            + "或显式 UAVFIRE_TRIAL_ALLOW_UNLIMITED=true");
        }
        if (isExpired()) {
            throw new IllegalStateException(expiredMessage());
        }
    }

    public boolean schedulesShutdown() {
        return mode == Mode.UNTIL && !isExpired();
    }

    public boolean isExpired() {
        if (mode == Mode.OFF) {
            return false;
        }
        return !clock.instant().isBefore(expiresAt);
    }

    public Duration remaining() {
        if (mode == Mode.OFF) {
            return Duration.ofDays(3650);
        }
        Duration remaining = Duration.between(clock.instant(), expiresAt);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public String expiredMessage() {
        if (mode == Mode.OFF) {
            return "试用截止未启用";
        }
        if (EXPIRES_AT.equals(expiresAt)) {
            return EXPIRED_MESSAGE;
        }
        return "试用授权已于 " + display() + " 到期";
    }

    public String display() {
        if (mode == Mode.OFF) {
            return production && allowUnlimited
                    ? "生产环境已显式关闭试用截止"
                    : "试用截止未启用";
        }
        if (EXPIRES_AT.equals(expiresAt)) {
            return EXPIRES_AT_DISPLAY;
        }
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(expiresAt.atOffset(java.time.ZoneOffset.ofHours(8)));
    }

    private static Mode parseMode(String raw) {
        if (raw == null || raw.isBlank() || "off".equalsIgnoreCase(raw)) {
            return Mode.OFF;
        }
        if ("until".equalsIgnoreCase(raw)) {
            return Mode.UNTIL;
        }
        throw new IllegalStateException("无法识别的试用模式 uavfire.trial.mode=" + raw + "，只接受 off 或 until");
    }

    private static Instant parseExpiresAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return EXPIRES_AT;
        }
        String text = raw.trim();
        try {
            return Instant.parse(text);
        } catch (RuntimeException ignored) {
            return OffsetDateTime.parse(text).toInstant();
        }
    }

    public String modeName() {
        return mode.name().toLowerCase(Locale.ROOT);
    }
}
