package com.dji.sample.manage.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DualStreamCommandDTO {

    private String commandId;

    private String droneSn;

    private String action;

    private String status;

    private String message;

    private Long issuedAt;

    private Long ackedAt;
}
