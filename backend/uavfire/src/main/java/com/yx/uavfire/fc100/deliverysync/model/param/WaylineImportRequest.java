package com.yx.uavfire.fc100.deliverysync.model.param;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WaylineImportRequest {
    private String missionNo;
    private String waylineId;
    private String kml;
}
