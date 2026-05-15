package com.yx.uavfire.fc100.payload.service;

import com.yx.uavfire.fc100.payload.model.param.PayloadConfirmReleaseParam;

public interface PayloadService {
    void markReleasePending(String missionNo, String operatorId, String clientIp, String requestId);
    void confirmRelease(String missionNo, PayloadConfirmReleaseParam param,
                         String clientIp, String requestId);
    void markReleaseFailed(String missionNo, String operatorId, String reason,
                            String clientIp, String requestId);
    void retryRelease(String missionNo, String operatorId, String clientIp, String requestId);
}
