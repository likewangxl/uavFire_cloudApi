package com.yx.uavfire.fc100.mission.model.enums;

import java.util.Set;

/**
 * 灭火任务状态枚举。完整 20 个状态见 spec §4.1。
 * ARCHIVED 是绝对终态，不可转出。
 */
public enum FireMissionStatus {
    CREATED,
    WAITING_REVIEW,
    APPROVED,
    ROUTE_GENERATED,
    ROUTE_EXPORTED,
    SENT_TO_DELIVERY,
    ACCEPTED_BY_PILOT,
    IN_PROGRESS,
    PAYLOAD_RELEASE_PENDING,
    PAYLOAD_RELEASED,
    RETURNING,
    REVIEWING,
    COMPLETED,
    REJECTED,
    CANCELLED,
    FAILED,
    MANUAL_TAKEOVER,
    PAYLOAD_RELEASE_FAILED,
    RETURN_FAILED,
    ARCHIVED;

    /** 终态：不能再转出（spec §4.1 + §4.4 矩阵中 ARCHIVED 行全空） */
    public boolean isTerminal() {
        return this == ARCHIVED;
    }

    /** 可以进入 ARCHIVED 的状态集合 */
    public boolean canArchive() {
        return Set.of(COMPLETED, FAILED, REJECTED, CANCELLED).contains(this);
    }

    /** 处于"飞机已在空中"阶段，禁止 cancel（spec §4.4） */
    public boolean isInFlight() {
        return Set.of(IN_PROGRESS, PAYLOAD_RELEASE_PENDING, PAYLOAD_RELEASED,
                      RETURNING, PAYLOAD_RELEASE_FAILED, RETURN_FAILED).contains(this);
    }
}
