package com.yx.uavfire.manage.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DualStreamLiveGroupDTO {

    private String droneSn;

    private String connectionState;

    private String sessionState;

    private String liveStatus;

    private String currentMode;

    private String statusMessage;

    private String visibleState;

    private String thermalState;

    private String statusReason;

    private String playbackStatus;

    private String visiblePlayUrl;

    private String thermalPlayUrl;

    private String lastCommandAction;

    private String lastCommandStatus;

    private Boolean visibleSupported;

    private Boolean thermalSupported;

    private Double thermalCenterTemperatureC;

    private String detectorIntent;

    private String detectorState;

    private String detectorHealth;

    private String detectorReason;

    private Long detectorObservedAt;

    private Long detectorIntentVersion;
}
