package com.yx.uavfire.miniapp.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "miniapp")
public class MiniAppProperties {

    /** Mobile APIs stay closed until an environment explicitly enables them. */
    private boolean enabled;

    private final Wechat wechat = new Wechat();

    private final FlightControl flightControl = new FlightControl();

    private final Dashboard dashboard = new Dashboard();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Wechat getWechat() {
        return wechat;
    }

    public FlightControl getFlightControl() {
        return flightControl;
    }

    public Dashboard getDashboard() {
        return dashboard;
    }

    public static class Wechat {
        private boolean enabled;
        private String appId = "";
        private String appSecret = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public String getAppSecret() {
            return appSecret;
        }

        public void setAppSecret(String appSecret) {
            this.appSecret = appSecret;
        }
    }

    public static class FlightControl {
        private boolean enabled;
        /**
         * Exact accepted tuples: MODEL|CONTROLLER_OR_DOCK|PAYLOAD|POSITION.
         * Empty by default so enabling the global switch alone cannot authorize flight.
         */
        private List<String> verifiedCombinationKeys = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getVerifiedCombinationKeys() {
            return verifiedCombinationKeys;
        }

        public void setVerifiedCombinationKeys(List<String> verifiedCombinationKeys) {
            this.verifiedCombinationKeys = verifiedCombinationKeys == null
                    ? new ArrayList<>() : new ArrayList<>(verifiedCombinationKeys);
        }
    }

    public static class Dashboard {
        /**
         * Current MSDK state has no workspace ownership field. This adapter is therefore
         * opt-in and must only be enabled in a single-workspace or gateway-filtered deployment.
         */
        private boolean liveDeviceSummaryEnabled;

        public boolean isLiveDeviceSummaryEnabled() {
            return liveDeviceSummaryEnabled;
        }

        public void setLiveDeviceSummaryEnabled(boolean liveDeviceSummaryEnabled) {
            this.liveDeviceSummaryEnabled = liveDeviceSummaryEnabled;
        }
    }
}
