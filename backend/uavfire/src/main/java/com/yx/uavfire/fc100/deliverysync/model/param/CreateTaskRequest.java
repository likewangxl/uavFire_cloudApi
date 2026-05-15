package com.yx.uavfire.fc100.deliverysync.model.param;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateTaskRequest {
    private String workspaceId;
    private String deviceSn;
    private String missionNo;
    private String waylineKmzObjectKey;
    private String waylineKmzSha256;
}
