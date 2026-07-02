package com.yx.uavfire.fc100.event.model.param;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class FireEventActionParam {
    @NotBlank
    private String operatorId;
    private String reason;
}
