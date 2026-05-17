package com.yx.uavfire.wayline.agent.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@NoArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WaylineDispatchDataDTO {

    private String missionId;

    private String kmzUrl;

    private String kmzFilename;

    private String kmzMd5;

    private List<Integer> waylineIds;

    private Double rthAltitude;
}
