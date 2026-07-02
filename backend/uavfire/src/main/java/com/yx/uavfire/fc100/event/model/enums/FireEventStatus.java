package com.yx.uavfire.fc100.event.model.enums;

public enum FireEventStatus {
    /** 刚接收，未处理 */
    NEW,
    CANDIDATE,
    /** 置信度 < 0.75，不自动建任务 */
    LOW_CONFIDENCE,
    /** 已建任务 */
    MISSION_CREATED,
    /** 人工忽略 */
    IGNORED
}
