package com.dji.sample.wayline.model.param;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class PreparePlannedWaylineTaskParam {

    @NotBlank
    private String dockSn;

    private String droneSn;

    private Long executeTime;

    private String taskType;
}
