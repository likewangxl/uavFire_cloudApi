package com.yx.uavfire.fc100.event.model.param;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yx.uavfire.fc100.event.model.dto.FireGeoSnapshotDTO;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Map;

@Data
public class FireEventCreateParam {

    @NotBlank @Size(max = 64)
    private String eventId;

    @NotBlank @Size(max = 32)
    private String source;       // M4T / MANUAL / TEST

    @Size(max = 64)
    private String deviceSn;

    @NotNull @DecimalMin("0.0") @DecimalMax("1.0")
    private BigDecimal confidence;

    @Size(max = 16)
    private String fireLevel;     // LOW / MEDIUM / HIGH / UNKNOWN

    @DecimalMin("-90.0") @DecimalMax("90.0")
    private Double lat;

    @DecimalMin("-180.0") @DecimalMax("180.0")
    private Double lng;

    private Double alt;

    @Pattern(regexp = "ELLIPSOID|AGL|ASL")
    private String altitudeReference;

    private Double thermalTemperature;

    private Map<String, Double> thermalMeasureRoi;

    private Map<String, Double> visibleRoi;

    @JsonProperty("geo_snapshot")
    @JsonAlias("geoSnapshot")
    private FireGeoSnapshotDTO geoSnapshot;

    private String geoMethod;

    private Double geoErrorRadiusM;

    private String geoQuality;

    private Long geoSourceTs;

    private Double aircraftLat;

    private Double aircraftLng;

    private Double aircraftAlt;

    private Double gimbalPitch;

    private Double gimbalYaw;

    private Double gimbalRoll;

    private String thermalRoi;

    @Pattern(regexp = "K|C")
    private String temperatureUnit;

    private String thermalImageUrl;

    private String visibleImageUrl;

    @Pattern(regexp = "MANUAL_CONFIRM|DRY_RUN|CONTROLLED_TEST_AUTO")
    private String releasePolicy;

    @Pattern(regexp = "OFFICIAL_HOOK_MANUAL|DELIVERY_SYNC_REMOTE|PSDK_RELEASE")
    private String releaseExecutionMode;

    /** ISO8601 字符串，服务端转 epoch ms */
    @NotBlank
    private String timestamp;

    @Size(max = 64)
    private String workspaceId;
}
