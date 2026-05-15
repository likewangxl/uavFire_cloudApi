package com.yx.uavfire.fc100.mission.model.enums;

/**
 * 状态机触发事件。20 个事件名称对应 spec §4.4 from→to 矩阵的列头。
 */
public enum FireMissionEvent {
    APPROVE,
    REJECT,
    CANCEL,
    GEN_WP,
    EXP_KMZ,
    CREATE_DELIVERY_TASK,
    START_DELIVERY,
    MARK_RELEASE_PENDING,
    CONFIRM_RELEASE,
    MARK_RELEASE_FAILED,
    RETRY_RELEASE,
    MARK_RETURNING,
    MARK_RETURN_COMPLETED,
    MARK_RETURN_FAILED,
    TAKEOVER,
    RESOLVE_TAKEOVER_OK,
    RESOLVE_TAKEOVER_FAILED,
    SUBMIT_REVIEW,
    ARCHIVE,
    FORCE_FAIL
}
