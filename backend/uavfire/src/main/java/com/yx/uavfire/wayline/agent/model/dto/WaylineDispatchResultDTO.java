package com.yx.uavfire.wayline.agent.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WaylineDispatchResultDTO {

    private String missionId;

    private Integer result;

    private Integer msdkErrorCode;

    private String msdkErrorMsg;

    private String msdkMissionFileName;
}
