package com.yx.uavfire.fc100.operation.model.enums;

public enum OperationIncidentStatus {
    CANDIDATE,
    CONFIRMED,
    DISPATCHING,
    RESPONDING,
    RECHECKING,
    RESOLVED,
    ARCHIVED,
    FALSE_ALARM,
    /**
     * 评审报告 20260702 批准新增：真火情处置中途被中止，不计入误报。
     */
    ABORTED
}
