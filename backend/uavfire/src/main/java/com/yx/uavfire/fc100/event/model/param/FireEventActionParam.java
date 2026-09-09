package com.yx.uavfire.fc100.event.model.param;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class FireEventActionParam {
    @NotBlank
    private String operatorId;
    @Size(max = 1000)
    private String reason;
}
