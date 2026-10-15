package com.yx.uavfire.fc100.mission.controller;

import com.yx.uavfire.fc100.common.ApiResult;
import com.yx.uavfire.fc100.common.idempotency.Idempotent;
import com.yx.uavfire.fc100.mission.model.dto.FireMissionDTO;
import com.yx.uavfire.fc100.mission.model.dto.MissionLogDTO;
import com.yx.uavfire.fc100.mission.service.Fc100OperatorResolver;
import com.yx.uavfire.fc100.mission.service.MissionApprovalService;
import com.yx.uavfire.fc100.mission.service.MissionLogService;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus;
import com.yx.uavfire.fc100.mission.model.param.MissionApproveParam;
import com.yx.uavfire.fc100.mission.model.param.MissionRejectParam;
import com.yx.uavfire.fc100.mission.model.param.MissionResolveTakeoverParam;
import com.yx.uavfire.fc100.mission.model.param.SimpleOperatorParam;
import com.yx.uavfire.fc100.mission.service.FireMissionService;
import com.yx.uavfire.fc100.mission.service.MissionStateMachine;
import com.yx.uavfire.fc100.mission.service.TransitCommand;
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
import java.util.Map;

/**
 * spec §4.5 任务相关端点。所有 POST 状态推进强制 @Idempotent。
 */
@RestController
@RequestMapping("/api/fire/missions")
public class FireMissionController {

    private final MissionStateMachine sm;
    private final FireMissionService missionService;
    private final MissionLogService missionLogService;
    private final MissionApprovalService approvalService;
    private final Fc100OperatorResolver operators;

    public FireMissionController(MissionStateMachine sm, FireMissionService ms,
                                  MissionLogService missionLogService,
                                  MissionApprovalService approvalService, Fc100OperatorResolver operators) {
        this.sm = sm;
        this.missionService = ms;
        this.missionLogService = missionLogService;
        this.approvalService = approvalService;
        this.operators = operators;
    }

    // ============= 查询 =============

    @GetMapping("/{no}")
    public ApiResult<FireMissionDTO> detail(@PathVariable("no") String no) {
        return ApiResult.success(missionService.detail(no));
    }

    @GetMapping
    public ApiResult<List<FireMissionDTO>> list(
            @RequestParam(value = "workspaceId", required = false) String workspaceId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ApiResult.success(missionService.list(workspaceId, status, page, size));
    }

    @GetMapping("/{no}/logs")
    public ApiResult<List<MissionLogDTO>> logs(@PathVariable("no") String no,
                                                @RequestParam(defaultValue = "200") int limit) {
        return ApiResult.success(missionLogService.listByMissionNo(no, limit));
    }

    // ============= 状态推进 =============

    @PostMapping("/{no}/approve")
    @Idempotent("mission.approve")
    public ApiResult<FireMissionEntity> approve(@PathVariable("no") String no,
                                                 @Valid @RequestBody MissionApproveParam p,
                                                 HttpServletRequest req) {
        Fc100OperatorResolver.ResolvedOperator operator = operators.resolve(req, p.getOperatorId());
        return ApiResult.success(approvalService.approve(
                no, p, operator.operatorId, operator.role, req));
    }

    @PostMapping("/{no}/reject")
    @Idempotent("mission.reject")
    public ApiResult<FireMissionEntity> reject(@PathVariable("no") String no,
                                                @Valid @RequestBody MissionRejectParam p,
                                                HttpServletRequest req) {
        return ApiResult.success(sm.transit(cmd(no, FireMissionEvent.REJECT, p.getOperatorId(), req)
            .expectedFrom(FireMissionStatus.WAITING_REVIEW)
            .payload(Map.of("reason", p.getReason() != null ? p.getReason() : ""))
            .build()));
    }

    @PostMapping("/{no}/cancel")
    @Idempotent("mission.cancel")
    public ApiResult<FireMissionEntity> cancel(@PathVariable("no") String no,
                                                @Valid @RequestBody SimpleOperatorParam p,
                                                HttpServletRequest req) {
        return ApiResult.success(sm.transit(cmd(no, FireMissionEvent.CANCEL, p.getOperatorId(), req)
            .payload(Map.of("reason", p.getReason() != null ? p.getReason() : ""))
            .build()));
    }

    @PostMapping("/{no}/archive")
    @Idempotent("mission.archive")
    public ApiResult<FireMissionEntity> archive(@PathVariable("no") String no,
                                                 @Valid @RequestBody SimpleOperatorParam p,
                                                 HttpServletRequest req) {
        return ApiResult.success(sm.transit(cmd(no, FireMissionEvent.ARCHIVE, p.getOperatorId(), req).build()));
    }

    @PostMapping("/{no}/force-fail")
    @Idempotent("mission.force-fail")
    public ApiResult<FireMissionEntity> forceFail(@PathVariable("no") String no,
                                                   @Valid @RequestBody SimpleOperatorParam p,
                                                   HttpServletRequest req) {
        operators.requireForceFail(req);
        return ApiResult.success(sm.transit(cmd(no, FireMissionEvent.FORCE_FAIL, p.getOperatorId(), req)
            .payload(Map.of("reason", p.getReason() != null ? p.getReason() : ""))
            .build()));
    }

    @PostMapping("/{no}/pilot-accept")
    @Idempotent("mission.pilot-accept")
    public ApiResult<FireMissionEntity> pilotAccept(@PathVariable("no") String no,
                                                     @Valid @RequestBody SimpleOperatorParam p,
                                                     HttpServletRequest req) {
        return ApiResult.success(sm.transit(cmd(no, FireMissionEvent.PILOT_ACCEPT, p.getOperatorId(), req)
            .expectedFrom(FireMissionStatus.SENT_TO_DELIVERY)
            .build()));
    }

    @PostMapping("/{no}/takeover")
    @Idempotent("mission.takeover")
    public ApiResult<FireMissionEntity> takeover(@PathVariable("no") String no,
                                                  @Valid @RequestBody SimpleOperatorParam p,
                                                  HttpServletRequest req) {
        return ApiResult.success(sm.transit(cmd(no, FireMissionEvent.TAKEOVER, p.getOperatorId(), req)
            .payload(Map.of("reason", p.getReason() != null ? p.getReason() : ""))
            .build()));
    }

    @PostMapping("/{no}/resolve-takeover")
    @Idempotent("mission.resolve-takeover")
    public ApiResult<FireMissionEntity> resolveTakeover(@PathVariable("no") String no,
                                                         @Valid @RequestBody MissionResolveTakeoverParam p,
                                                         HttpServletRequest req) {
        FireMissionEvent ev = "OK".equals(p.getResult())
            ? FireMissionEvent.RESOLVE_TAKEOVER_OK
            : FireMissionEvent.RESOLVE_TAKEOVER_FAILED;
        return ApiResult.success(sm.transit(cmd(no, ev, p.getOperatorId(), req)
            .remark(p.getRemark()).build()));
    }

    // ============= 返航相关（Day 6 新增） =============

    @PostMapping("/{no}/mark-returning")
    @Idempotent("mission.mark-returning")
    public ApiResult<FireMissionEntity> markReturning(@PathVariable("no") String no,
                                                       @Valid @RequestBody SimpleOperatorParam p,
                                                       HttpServletRequest req) {
        return ApiResult.success(sm.transit(cmd(no, FireMissionEvent.MARK_RETURNING, p.getOperatorId(), req)
            .build()));
    }

    @PostMapping("/{no}/mark-return-completed")
    @Idempotent("mission.mark-return-completed")
    public ApiResult<FireMissionEntity> markReturnCompleted(@PathVariable("no") String no,
                                                             @Valid @RequestBody SimpleOperatorParam p,
                                                             HttpServletRequest req) {
        return ApiResult.success(sm.transit(cmd(no, FireMissionEvent.MARK_RETURN_COMPLETED, p.getOperatorId(), req)
            .build()));
    }

    @PostMapping("/{no}/mark-return-failed")
    @Idempotent("mission.mark-return-failed")
    public ApiResult<FireMissionEntity> markReturnFailed(@PathVariable("no") String no,
                                                          @Valid @RequestBody SimpleOperatorParam p,
                                                          HttpServletRequest req) {
        return ApiResult.success(sm.transit(cmd(no, FireMissionEvent.MARK_RETURN_FAILED, p.getOperatorId(), req)
            .payload(Map.of("reason", p.getReason() != null ? p.getReason() : ""))
            .build()));
    }

    // ============= helpers =============

    private TransitCommand.TransitCommandBuilder cmd(String missionNo, FireMissionEvent ev,
                                                      String operatorId, HttpServletRequest req) {
        Fc100OperatorResolver.ResolvedOperator operator = operators.resolve(req, operatorId);
        return TransitCommand.builder()
            .missionNo(missionNo)
            .event(ev)
            .operatorId(operator.operatorId)
            .operatorRole(operator.role)
            .clientIp(req.getRemoteAddr())
            .requestId(req.getHeader("X-Request-Id"))
            .idempotencyKey(req.getHeader("X-Idempotency-Key"));
    }
}
