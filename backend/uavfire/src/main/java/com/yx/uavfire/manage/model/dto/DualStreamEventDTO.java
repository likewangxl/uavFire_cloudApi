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

    private String eventId;

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

    @JsonProperty("visible_roi")
    @JsonAlias("visibleRoi")
    private Map<String, Double> visibleRoi;

    private String visibleClass;

    private String modelVersion;

    private String modelSha256;

    /** SHA-256 of the exact JPEG persisted by the agent for this inference. */
    @JsonProperty("evidence_sha256")
    @JsonAlias("evidenceSha256")
    private String evidenceSha256;

    /** Capture time of the persisted evidence frame (epoch milliseconds). */
    @JsonProperty("evidence_captured_at")
    @JsonAlias("evidenceCapturedAt")
    private Long evidenceCapturedAt;

    /** Set to VERIFIED only after the backend has matched URL, hash and capture time. */
    @JsonProperty("evidence_status")
    @JsonAlias("evidenceStatus")
    private String evidenceStatus;

    private Long inferenceMs;

    private List<Map<String, Object>> thermalMeasurements;

    @JsonProperty("geo_snapshot")
    @JsonAlias("geoSnapshot")
    private FireGeoSnapshotDTO geoSnapshot;

    private Double fireLat;

    private Double fireLng;

    private Double fireAlt;

    private String geoQuality;

    private Double geoErrorRadiusM;

    private String geoMethod;
}
