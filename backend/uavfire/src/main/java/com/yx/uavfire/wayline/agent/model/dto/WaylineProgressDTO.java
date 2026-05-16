package com.yx.uavfire.wayline.agent.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WaylineProgressDTO {

    private String missionId;

    private String missionFileName;

    private Integer waylineId;

    private Integer currentWaypointIndex;

    private Integer totalWaypoints;

    private Integer percent;

    private Aircraft aircraft;

    private Integer batteryPercent;

    private Integer rcSignalDbm;

    private String rtkStatus;

    @Data
    @NoArgsConstructor
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Aircraft {
        private Double lat;
        private Double lng;
        private Double altEllipsoid;
        private Double altRelative;
        private Double speed;
        private Double yaw;
    }
}
