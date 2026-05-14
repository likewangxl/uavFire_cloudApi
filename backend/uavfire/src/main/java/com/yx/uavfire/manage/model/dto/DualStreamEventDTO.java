package com.yx.uavfire.manage.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

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
}
