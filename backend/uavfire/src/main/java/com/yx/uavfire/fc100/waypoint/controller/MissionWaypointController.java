package com.yx.uavfire.fc100.waypoint.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yx.uavfire.fc100.common.ApiResult;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.common.idempotency.Idempotent;
import com.yx.uavfire.fc100.safety.model.dto.SafetyCheckResult;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckPhase;
import com.yx.uavfire.fc100.safety.service.SafetyCheckService;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent;
import com.yx.uavfire.fc100.mission.service.MissionStateMachine;
import com.yx.uavfire.fc100.mission.service.TransitCommand;
import com.yx.uavfire.fc100.waypoint.model.dto.MissionWaypointDTO;
import com.yx.uavfire.fc100.waypoint.model.param.WaypointGenerateParam;
import com.yx.uavfire.fc100.waypoint.service.WaypointPlannerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/fire/missions/{no}/waypoints")
public class MissionWaypointController {

    private final WaypointPlannerService planner;
    private final FireMissionMapper missionMapper;
    private final MissionStateMachine sm;
    private final SafetyCheckService safety;

    public MissionWaypointController(WaypointPlannerService p,
                                      FireMissionMapper m,
                                      MissionStateMachine sm,
                                      SafetyCheckService safety) {
        this.planner = p;
        this.missionMapper = m;
        this.sm = sm;
        this.safety = safety;
    }

    @PostMapping("/generate")
    @Idempotent("mission.waypoints.generate")
    public ApiResult<List<MissionWaypointDTO>> generate(@PathVariable("no") String no,
                                                         @Valid @RequestBody WaypointGenerateParam param,
                                                         HttpServletRequest req) {
        FireMissionEntity m = missionMapper.selectOne(
            new QueryWrapper<FireMissionEntity>().eq("mission_no", no).eq("deleted", 0));
        if (m == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.MISSION_NOT_FOUND, no);
        }

        // 0. BEFORE_ROUTE 安全校验（takeoff 必填 / 距离上限 / 风速 ERROR 等）
        SafetyCheckResult sc = safety.check(SafetyCheckPhase.BEFORE_ROUTE, no);
        if (!sc.isPassed()) {
            throw new Fc100BusinessException(Fc100ErrorCode.SAFETY_CHECK_FAILED,
                "safety check failed: " + sc.getIssues().toString());
        }

        // 1. 计算航点（含距离/航线长度校验）
        List<MissionWaypointDTO> wps = planner.plan(param);
        // 2. 落库新版本
        planner.persistForMission(m.getId(), wps);
        // 3. 状态机推进 APPROVED|ROUTE_GENERATED|ROUTE_EXPORTED → ROUTE_GENERATED
        sm.transit(TransitCommand.builder()
            .missionNo(no)
            .event(FireMissionEvent.GEN_WP)
            .operatorId(param.getOperatorId())
            .clientIp(req.getRemoteAddr())
            .requestId(req.getHeader("X-Request-Id"))
            .idempotencyKey(req.getHeader("X-Idempotency-Key"))
            .build());

        return ApiResult.success(wps);
    }

    @GetMapping
    public ApiResult<List<MissionWaypointDTO>> list(@PathVariable("no") String no) {
        FireMissionEntity m = missionMapper.selectOne(
            new QueryWrapper<FireMissionEntity>().eq("mission_no", no).eq("deleted", 0));
        if (m == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.MISSION_NOT_FOUND, no);
        }
        return ApiResult.success(planner.listLatest(m.getId()));
    }
}
