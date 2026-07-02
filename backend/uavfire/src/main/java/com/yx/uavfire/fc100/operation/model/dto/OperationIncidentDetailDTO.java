package com.yx.uavfire.fc100.operation.model.dto;

import com.yx.uavfire.fc100.operation.model.entity.OperationAssignmentEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class OperationIncidentDetailDTO extends OperationIncidentDTO {
    private List<OperationAssignmentEntity> assignments;
    private List<OperationTimelineItem> timeline;
}
