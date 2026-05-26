package com.yx.uavfire.manage.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.Map;

@Data
@NoArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DualStreamEventDTO {

    private String taskId;

    private String droneSn;

    private Long sourceTs;

    private Double visibleScore;

    private Double thermalScore;

    private Double fusionScore;

    private String riskLevel;

    private String analysisChannel;

    private String reviewStatus;

    @JsonProperty("visible_image_url")
    private String visibleImageUrl;

    @JsonProperty("thermal_image_url")
    private String thermalImageUrl;

    private Double thermalTemperature;

    @JsonProperty("thermal_measure_roi")
    private Map<String, Double> thermalMeasureRoi;
}
