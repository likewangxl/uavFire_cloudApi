package com.yx.uavfire.fc100.mission.model.param;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 通用：只需 operatorId（+ 可选 reason / remark）的端点用此 param。 */
@Data
public class SimpleOperatorParam {
    @NotBlank private String operatorId;
    private String reason;
    private String remark;
}
