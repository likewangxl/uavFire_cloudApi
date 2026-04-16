package com.dji.sdk.cloudapi.control;

import com.dji.sdk.common.BaseModel;

import javax.validation.constraints.NotEmpty;
import java.util.List;

public class CloudControlReleaseRequest extends BaseModel {

    @NotEmpty
    private List<String> controlKeys;

    public CloudControlReleaseRequest() {
    }

    @Override
    public String toString() {
        return "CloudControlReleaseRequest{" +
                "controlKeys=" + controlKeys +
                '}';
    }

    public List<String> getControlKeys() {
        return controlKeys;
    }

    public CloudControlReleaseRequest setControlKeys(List<String> controlKeys) {
        this.controlKeys = controlKeys;
        return this;
    }
}
