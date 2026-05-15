package com.yx.uavfire.fc100.mission.model.param;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

@Data
public class MissionResolveTakeoverParam {
    @NotBlank private String operatorId;
    /** OK | FAILED */
    @NotBlank @Pattern(regexp = "OK|FAILED")
    private String result;
    private String remark;
}
