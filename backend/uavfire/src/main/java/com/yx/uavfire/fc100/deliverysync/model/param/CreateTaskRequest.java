package com.yx.uavfire.fc100.deliverysync.model.param;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CreateTaskRequest {
    private String workspaceId;
    private String deviceSn;
    private String missionNo;
    private String taskName;
    private String missionId;
    private String remark;
    private List<String> notifies;
    private String waylineKmzObjectKey;
    private String waylineKmzSha256;
}
