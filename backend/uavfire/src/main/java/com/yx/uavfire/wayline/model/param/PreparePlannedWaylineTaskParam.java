package com.yx.uavfire.wayline.model.param;

import lombok.Data;

@Data
public class PreparePlannedWaylineTaskParam {

    /** 非空表示走 dock 路径,空表示走 agent (RC + DJI aircraft) 路径。 */
    private String dockSn;

    private String droneSn;

    private Long executeTime;

    private Long beginTime;

    private Long endTime;

    private String taskType;

    /** Dock ready 条件:最低起飞电量百分比;agent 路径忽略。 */
    private Integer minBattery;

    /** Dock 仿真开关;agent 路径忽略。 */
    private Boolean simulate;

    private Double simulateLat;

    private Double simulateLng;
}
