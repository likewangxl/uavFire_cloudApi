package com.yx.uavfire.fc100.mission.model.enums;

public enum ReleasePolicy {
    MANUAL_CONFIRM,
    DRY_RUN,
    CONTROLLED_TEST_AUTO;

    public static ReleasePolicy fromDb(String value) {
        if (value == null || value.isBlank()) {
            return MANUAL_CONFIRM;
        }
        return ReleasePolicy.valueOf(value);
    }
}
