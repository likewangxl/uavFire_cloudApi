package com.yx.uavfire.fc100.operation.service;

import com.yx.uavfire.fc100.operation.model.dto.OperationIncidentDTO;
import com.yx.uavfire.fc100.operation.model.dto.OperationIncidentDetailDTO;
import com.yx.uavfire.fc100.operation.model.dto.OperationTimelineItem;
import com.yx.uavfire.fc100.operation.model.entity.OperationAssignmentEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationIncidentEntity;
import com.yx.uavfire.fc100.operation.model.param.AssignOperationResourceParam;
import com.yx.uavfire.fc100.operation.model.param.CreateOperationIncidentParam;
import com.yx.uavfire.fc100.operation.model.param.OperationActionParam;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

public interface OperationIncidentService {

    OperationIncidentDTO create(CreateOperationIncidentParam param);

    List<OperationIncidentDTO> list(String status, String level, int page, int size);

    OperationIncidentDetailDTO detail(Long id);

    List<OperationTimelineItem> timeline(Long id);

    OperationAssignmentEntity assignMonitor(Long id, AssignOperationResourceParam param);

    OperationAssignmentEntity assignDelivery(Long id, AssignOperationResourceParam param);

    OperationIncidentEntity dispatch(Long id, OperationActionParam param, HttpServletRequest req);

    OperationIncidentEntity markFalseAlarm(Long id, OperationActionParam param, HttpServletRequest req);

    OperationIncidentEntity abort(Long id, OperationActionParam param, HttpServletRequest req);

    OperationIncidentEntity close(Long id, OperationActionParam param, HttpServletRequest req);
}
