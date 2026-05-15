package com.yx.uavfire.fc100.payload.model.param;

import lombok.Data;

import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.NotBlank;
import java.util.Map;

/**
 * spec §6.5 投放确认五项 checklist——所有 @AssertTrue 必须为 true 才能通过 Bean Validation。
 */
@Data
public class PayloadConfirmReleaseParam {

    @NotBlank private String operatorId;

    @AssertTrue(message = "must confirm FC100 arrived at drop point")
    private Boolean confirmedArrival;

    @AssertTrue(message = "must confirm no people risk")
    private Boolean confirmedNoPeopleRisk;

    @AssertTrue(message = "must confirm wind speed OK")
    private Boolean confirmedWindOk;

    @AssertTrue(message = "must confirm payload ready")
    private Boolean confirmedPayloadReady;

    @AssertTrue(message = "must confirm intent to release")
    private Boolean confirmedRelease;

    /** 各项勾选时间戳，前端注入，存到 payload_event.pre_release_checklist JSON 列 */
    private Map<String, Long> checklistTimestamps;
}
