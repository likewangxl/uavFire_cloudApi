package com.yx.uavfire.fc100.operation.controller;

import com.yx.uavfire.fc100.common.ApiResult;
import com.yx.uavfire.fc100.common.idempotency.Idempotent;
import com.yx.uavfire.fc100.operation.model.dto.OperationIncidentDTO;
import com.yx.uavfire.fc100.operation.model.dto.OperationIncidentDetailDTO;
import com.yx.uavfire.fc100.operation.model.dto.OperationTimelineItem;
import com.yx.uavfire.fc100.operation.model.entity.OperationAssignmentEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationIncidentEntity;
import com.yx.uavfire.fc100.operation.model.param.AssignOperationResourceParam;
import com.yx.uavfire.fc100.operation.model.param.CreateOperationIncidentParam;
import com.yx.uavfire.fc100.operation.model.param.OperationActionParam;
import com.yx.uavfire.fc100.operation.service.OperationIncidentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/operations/incidents")
public class OperationIncidentController {

    private final OperationIncidentService service;

    public OperationIncidentController(OperationIncidentService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResult<OperationIncidentDTO> create(@Valid @RequestBody CreateOperationIncidentParam param) {
        return ApiResult.success(service.create(param));
    }

    @GetMapping
    public ApiResult<List<OperationIncidentDTO>> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "level", required = false) String level,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ApiResult.success(service.list(status, level, page, size));
    }

    @GetMapping("/{id}")
    public ApiResult<OperationIncidentDetailDTO> detail(@PathVariable("id") Long id) {
        return ApiResult.success(service.detail(id));
    }

    @GetMapping("/{id}/timeline")
    public ApiResult<List<OperationTimelineItem>> timeline(@PathVariable("id") Long id) {
        return ApiResult.success(service.timeline(id));
    }

    @PostMapping("/{id}/assign-monitor")
    public ApiResult<OperationAssignmentEntity> assignMonitor(
            @PathVariable("id") Long id,
            @Valid @RequestBody AssignOperationResourceParam param) {
        return ApiResult.success(service.assignMonitor(id, param));
    }

    @PostMapping("/{id}/assign-delivery")
    public ApiResult<OperationAssignmentEntity> assignDelivery(
            @PathVariable("id") Long id,
            @Valid @RequestBody AssignOperationResourceParam param) {
        return ApiResult.success(service.assignDelivery(id, param));
    }

    @PostMapping("/{id}/dispatch")
    @Idempotent("operation.incident.dispatch")
    public ApiResult<OperationIncidentEntity> dispatch(
            @PathVariable("id") Long id,
            @Valid @RequestBody OperationActionParam param,
            HttpServletRequest req) {
        return ApiResult.success(service.dispatch(id, param, req));
    }

    @PostMapping("/{id}/mark-false-alarm")
    @Idempotent("operation.incident.mark-false-alarm")
    public ApiResult<OperationIncidentEntity> markFalseAlarm(
            @PathVariable("id") Long id,
            @Valid @RequestBody OperationActionParam param,
            HttpServletRequest req) {
        return ApiResult.success(service.markFalseAlarm(id, param, req));
    }

    @PostMapping("/{id}/abort")
    @Idempotent("operation.incident.abort")
    public ApiResult<OperationIncidentEntity> abort(
            @PathVariable("id") Long id,
            @Valid @RequestBody OperationActionParam param,
            HttpServletRequest req) {
        return ApiResult.success(service.abort(id, param, req));
    }

    @PostMapping("/{id}/close")
    @Idempotent("operation.incident.close")
    public ApiResult<OperationIncidentEntity> close(
            @PathVariable("id") Long id,
            @Valid @RequestBody OperationActionParam param,
            HttpServletRequest req) {
        return ApiResult.success(service.close(id, param, req));
    }
}
