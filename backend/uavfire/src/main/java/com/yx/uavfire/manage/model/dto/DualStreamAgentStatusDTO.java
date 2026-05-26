package com.yx.uavfire.manage.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DualStreamAgentStatusDTO {

    private String droneSn;

    private String connectionState;

    private String message;

    private String liveStatus;

    private String currentMode;

    private String visibleState;

    private String thermalState;

    private String statusReason;

    private String playbackStatus;

    private String visiblePlayUrl;

    private String thermalPlayUrl;

    private Double thermalCenterTemperatureC;
}
