package com.yx.uavfire.msdk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "uavfire.msdk")
public class MsdkDeviceProperties {

    private String deviceToken = "";

    /** Commands older than this are kept as EXPIRED instead of being flown after a restart. */
    private long commandDispatchTtlMs = 15_000L;

    public String getDeviceToken() {
        return deviceToken;
    }

    public void setDeviceToken(String deviceToken) {
        this.deviceToken = deviceToken;
    }

    public long getCommandDispatchTtlMs() {
        return commandDispatchTtlMs;
    }

    public void setCommandDispatchTtlMs(long commandDispatchTtlMs) {
        this.commandDispatchTtlMs = commandDispatchTtlMs;
    }
}
