package com.yx.uavfire.manage.model.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yx.uavfire.fc100.event.model.dto.FireGeoSnapshotDTO;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.List;
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

    @JsonProperty("thermal_source_event_id")
    private String thermalSourceEventId;

    private Double thermalTemperature;

    @JsonProperty("thermal_measure_roi")
    private Map<String, Double> thermalMeasureRoi;

    private List<Map<String, Object>> thermalMeasurements;

    @JsonProperty("geo_snapshot")
    @JsonAlias("geoSnapshot")
    private FireGeoSnapshotDTO geoSnapshot;

    private String geoQuality;

    private Double geoErrorRadiusM;

    private String geoMethod;
}
