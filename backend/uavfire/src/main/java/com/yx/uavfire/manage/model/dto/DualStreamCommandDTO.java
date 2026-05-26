package com.yx.uavfire.manage.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@Data
@NoArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DualStreamCommandDTO {

    private String commandId;

    private String droneSn;

    private String action;

    private String status;

    private String message;

    @JsonProperty("task_id")
    private String taskId;

    @JsonProperty("source_ts")
    private Long sourceTs;

    @JsonProperty("thermal_measure_roi")
    private Map<String, Double> thermalMeasureRoi;

    @JsonProperty("thermal_image_url")
    private String thermalImageUrl;

    private Long issuedAt;

    private Long ackedAt;
}
