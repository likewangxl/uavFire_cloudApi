package com.yx.uavfire.fc100.operation.preflight;

import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;

public class PreflightBlockedException extends Fc100BusinessException {
    private final PreflightResult result;

    public PreflightBlockedException(PreflightResult result) {
        super(Fc100ErrorCode.SAFETY_CHECK_FAILED, "preflight blocked");
        this.result = result;
    }

    public PreflightResult getResult() {
        return result;
    }
}
