package com.dji.sdk.cloudapi.control;

import com.dji.sdk.common.BaseModel;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.util.List;

public class CloudControlAuthRequest extends BaseModel {

    @NotBlank
    private String userId;

    @NotBlank
    private String userCallsign;

    @NotEmpty
    private List<String> controlKeys;

    public CloudControlAuthRequest() {
    }

    @Override
    public String toString() {
        return "CloudControlAuthRequest{" +
                "userId='" + userId + '\'' +
                ", userCallsign='" + userCallsign + '\'' +
                ", controlKeys=" + controlKeys +
                '}';
    }

    public String getUserId() {
        return userId;
    }

    public CloudControlAuthRequest setUserId(String userId) {
        this.userId = userId;
        return this;
    }

    public String getUserCallsign() {
        return userCallsign;
    }

    public CloudControlAuthRequest setUserCallsign(String userCallsign) {
        this.userCallsign = userCallsign;
        return this;
    }

    public List<String> getControlKeys() {
        return controlKeys;
    }

    public CloudControlAuthRequest setControlKeys(List<String> controlKeys) {
        this.controlKeys = controlKeys;
        return this;
    }
}
