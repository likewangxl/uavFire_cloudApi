package com.yx.uavfire.trial;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "uavfire.trial")
public class TrialLicenseProperties {

    /** off | until. off is the dev/test default. */
    private String mode = "off";

    /** Used only when mode=until. The historical cutoff stays the default. */
    private String expiresAt = "2026-10-01T00:00:00+08:00";

    /** Production mode=off is refused unless this is explicitly true. */
    private boolean allowUnlimited = false;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(String expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isAllowUnlimited() {
        return allowUnlimited;
    }

    public void setAllowUnlimited(boolean allowUnlimited) {
        this.allowUnlimited = allowUnlimited;
    }
}
