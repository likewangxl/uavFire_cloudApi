package com.yx.uavfire.fc100.deliverysync.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryWaylineDTO {
    private String waylineId;
    private String waylineType;
    private String name;
    private Double distance;
    private Integer duration;
    private String flyToWaylineMode;
    private String finishAction;
    private String rcLostAction;
    private String turnMode;
    private String fingerprint;
    private Long createTime;
    private Long updateTime;
}
