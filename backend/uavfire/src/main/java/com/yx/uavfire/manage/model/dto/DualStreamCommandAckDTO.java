package com.yx.uavfire.manage.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DualStreamCommandAckDTO {

    private String commandId;

    private String status;

    private String message;
}
