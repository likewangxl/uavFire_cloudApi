package com.yx.uavfire.fc100.event.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FireGeoSnapshotDTO {
    private AircraftPosition aircraftPosition;
    private Attitude aircraftAttitude;
    private Attitude gimbalAttitude;
    private CameraModel cameraModel;
    private FrameSize frameSize;
    private ThermalRoi thermalRoi;
    private String rtkStatus;
    private Long sourceTs;

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AircraftPosition {
        private Double lat;
        private Double lng;
        private Double alt;
        private Double relativeAlt;
    }

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Attitude {
        private Double yaw;
        private Double pitch;
        private Double roll;
    }

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CameraModel {
        private String model;
        private Double focalLengthMm;
        private Double horizontalFovDeg;
        private Double verticalFovDeg;
    }

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FrameSize {
        private Integer width;
        private Integer height;
    }

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ThermalRoi {
        private Double x;
        private Double y;
        private Double width;
        private Double height;
    }
}
