package com.yx.uavfire.wayline.agent.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WaylineStateChangeDTO {

    private String missionId;

    private String msdkState;

    private String businessState;

    private String previousMsdkState;

    private String error;
}
