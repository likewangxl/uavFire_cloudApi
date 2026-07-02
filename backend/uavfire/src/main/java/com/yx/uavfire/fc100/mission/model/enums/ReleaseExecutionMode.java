package com.yx.uavfire.fc100.mission.model.enums;

public enum ReleaseExecutionMode {
    OFFICIAL_HOOK_MANUAL,
    DELIVERY_SYNC_REMOTE,
    PSDK_RELEASE;

    public static ReleaseExecutionMode fromDb(String value) {
        if (value == null || value.isBlank()) {
            return OFFICIAL_HOOK_MANUAL;
        }
        return ReleaseExecutionMode.valueOf(value);
    }
}
