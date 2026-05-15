package com.yx.uavfire.fc100.deliverysync.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.yx.uavfire.fc100.common.ApiResult;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.common.idempotency.Idempotent;
import com.yx.uavfire.fc100.deliverysync.DeliverySyncAdapter;
import com.yx.uavfire.fc100.deliverysync.config.DeliverySyncProperties;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryDeviceDTO;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryDeviceProperties;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskRef;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskStatus;
import com.yx.uavfire.fc100.deliverysync.model.param.CreateTaskRequest;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent;
import com.yx.uavfire.fc100.mission.service.MissionStateMachine;
import com.yx.uavfire.fc100.mission.service.TransitCommand;
import com.yx.uavfire.fc100.route.model.dto.RouteFileDTO;
import com.yx.uavfire.fc100.route.service.RouteExportService;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.util.List;

/** spec §4.5 — 5 个 Delivery 相关端点（spec API 路径已扁平到 /api/fire/...） */
@RestController
@RequestMapping("/api/fire")
public class DeliveryController {

    private final DeliverySyncAdapter adapter;
    private final DeliverySyncProperties props;
    private final FireMissionMapper missionMapper;
    private final RouteExportService routeService;
    private final MissionStateMachine sm;

    public DeliveryController(DeliverySyncAdapter a, DeliverySyncProperties p,
                               FireMissionMapper m, RouteExportService r, MissionStateMachine sm) {
        this.adapter = a; this.props = p; this.missionMapper = m;
        this.routeService = r; this.sm = sm;
    }

    @GetMapping("/delivery/devices")
    public ApiResult<List<DeliveryDeviceDTO>> devices(
            @RequestParam(value = "workspaceId", required = false) String ws) {
        return ApiResult.success(adapter.listDevices(ws != null ? ws : props.getWorkspaceId()));
    }

    @GetMapping("/delivery/devices/{sn}/properties")
    public ApiResult<DeliveryDeviceProperties> deviceProps(@PathVariable("sn") String sn) {
        return ApiResult.success(adapter.getDeviceProperties(sn));
    }

    @Data
    public static class CreateTaskParam {
        @NotBlank private String operatorId;
    }

    @PostMapping("/missions/{no}/delivery/create-task")
    @Idempotent("delivery.create-task")
    public ApiResult<DeliveryTaskRef> createTask(@PathVariable("no") String no,
                                                  @Valid @RequestBody CreateTaskParam p,
                                                  HttpServletRequest req) {
        FireMissionEntity m = missionMapper.selectOne(
            new QueryWrapper<FireMissionEntity>().eq("mission_no", no).eq("deleted", 0));
        if (m == null) throw new Fc100BusinessException(Fc100ErrorCode.MISSION_NOT_FOUND, no);
        RouteFileDTO file = routeService.getLatest(no);
        if (file == null) throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
            "no route file exported");

        DeliveryTaskRef ref = adapter.createTask(CreateTaskRequest.builder()
            .workspaceId(m.getWorkspaceId())
            .deviceSn(m.getAircraftSn())
            .missionNo(no)
            .waylineKmzObjectKey(file.getObjectKey())
            .waylineKmzSha256(file.getSign())
            .build());

        sm.transit(TransitCommand.builder()
            .missionNo(no).event(FireMissionEvent.CREATE_DELIVERY_TASK)
            .operatorId(p.getOperatorId())
            .clientIp(req.getRemoteAddr())
            .requestId(req.getHeader("X-Request-Id"))
            .idempotencyKey(req.getHeader("X-Idempotency-Key"))
            .build());

        missionMapper.update(null, new UpdateWrapper<FireMissionEntity>()
            .eq("id", m.getId()).set("dji_task_id", ref.getTaskId()));

        return ApiResult.success(ref);
    }

    @PostMapping("/missions/{no}/delivery/start-task")
    @Idempotent("delivery.start-task")
    public ApiResult<Void> startTask(@PathVariable("no") String no,
                                      @Valid @RequestBody CreateTaskParam p,
                                      HttpServletRequest req) {
        FireMissionEntity m = missionMapper.selectOne(
            new QueryWrapper<FireMissionEntity>().eq("mission_no", no).eq("deleted", 0));
        if (m == null) throw new Fc100BusinessException(Fc100ErrorCode.MISSION_NOT_FOUND, no);
        if (m.getDjiTaskId() == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                "delivery task not created");
        }
        adapter.startTask(m.getDjiTaskId());

        sm.transit(TransitCommand.builder()
            .missionNo(no).event(FireMissionEvent.START_DELIVERY)
            .operatorId(p.getOperatorId())
            .clientIp(req.getRemoteAddr())
            .requestId(req.getHeader("X-Request-Id"))
            .idempotencyKey(req.getHeader("X-Idempotency-Key"))
            .build());
        return ApiResult.success(null);
    }

    @GetMapping("/missions/{no}/delivery/status")
    public ApiResult<DeliveryTaskStatus> status(@PathVariable("no") String no) {
        FireMissionEntity m = missionMapper.selectOne(
            new QueryWrapper<FireMissionEntity>().eq("mission_no", no).eq("deleted", 0));
        if (m == null) throw new Fc100BusinessException(Fc100ErrorCode.MISSION_NOT_FOUND, no);
        if (m.getDjiTaskId() == null) return ApiResult.success(null);
        return ApiResult.success(adapter.queryTaskStatus(m.getDjiTaskId()));
    }
}
