package com.yx.uavfire.fc100.payload.model.param;

import lombok.Data;

import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.NotBlank;
import java.util.Map;

/**
 * Payload release confirmation checklist. Every checklist item must be true.
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

    @NotBlank
    private String confirmationToken;

    /** OFFICIAL_HOOK_MANUAL evidence note from the pilot/operator. */
    private String remoteHookRemark;

    /** Frontend checklist timestamps persisted as payload event JSON. */
    private Map<String, Long> checklistTimestamps;
}
