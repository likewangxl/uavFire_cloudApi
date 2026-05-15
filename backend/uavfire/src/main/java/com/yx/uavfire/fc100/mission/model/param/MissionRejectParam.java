package com.yx.uavfire.fc100.mission.model.param;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class MissionRejectParam {
    @NotBlank private String operatorId;
    private String reason;
}
