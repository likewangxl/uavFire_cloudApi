package com.yx.uavfire.fc100.mission.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus;
import com.yx.uavfire.fc100.mission.model.param.MissionApproveParam;
import com.yx.uavfire.fc100.safety.model.dto.SafetyCheckResult;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckPhase;
import com.yx.uavfire.fc100.safety.service.SafetyCheckService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs BEFORE_APPROVE against an in-memory copy. The mission row changes only after the check passes.
 */
@Service
public class MissionApprovalService {

    private final FireMissionMapper missionMapper;
    private final SafetyCheckService safety;
    private final MissionStateMachine stateMachine;

    public MissionApprovalService(FireMissionMapper missionMapper, SafetyCheckService safety,
                                  MissionStateMachine stateMachine) {
        this.missionMapper = missionMapper;
        this.safety = safety;
        this.stateMachine = stateMachine;
    }

    public FireMissionEntity approve(String missionNo, MissionApproveParam param, String operatorId,
                                     String operatorRole, HttpServletRequest request) {
        FireMissionEntity current = missionMapper.selectOne(new QueryWrapper<FireMissionEntity>()
                .eq("mission_no", missionNo)
                .eq("deleted", 0));
        if (current == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.MISSION_NOT_FOUND, missionNo);
        }
        FireMissionStatus from = FireMissionStatus.valueOf(current.getStatus());
        if (from != FireMissionStatus.WAITING_REVIEW && from != FireMissionStatus.CREATED) {
            throw new Fc100BusinessException(Fc100ErrorCode.STATUS_TRANSITION_FORBIDDEN,
                    "approve requires WAITING_REVIEW or CREATED but was " + from);
        }

        FireMissionEntity preview = new FireMissionEntity();
        BeanUtils.copyProperties(current, preview);
        applyApprovePayload(preview, param);
        preview.setApproverId(operatorId);
        SafetyCheckResult check = safety.check(SafetyCheckPhase.BEFORE_APPROVE, preview);
        if (check == null || !check.isPassed()) {
            String issues = check == null || check.getIssues() == null ? "" : check.getIssues().toString();
            throw new Fc100BusinessException(Fc100ErrorCode.SAFETY_CHECK_FAILED, "safety check failed: " + issues);
        }

        return stateMachine.transit(TransitCommand.builder()
                .missionNo(missionNo)
                .event(FireMissionEvent.APPROVE)
                .expectedFrom(from)
                .operatorId(operatorId)
                .operatorRole(operatorRole)
                .payload(buildApprovePayload(param))
                .remark(param.getRemark())
                .clientIp(request == null ? null : request.getRemoteAddr())
                .requestId(request == null ? null : request.getHeader("X-Request-Id"))
                .idempotencyKey(request == null ? null : request.getHeader("X-Idempotency-Key"))
                .build());
    }

    private static void applyApprovePayload(FireMissionEntity mission, MissionApproveParam param) {
        if (param.getAircraftSn() != null) mission.setAircraftSn(param.getAircraftSn());
        if (param.getPayloadId() != null) mission.setPayloadId(param.getPayloadId());
        if (param.getWaterLoadLiters() != null) mission.setWaterLoadLiters(param.getWaterLoadLiters());
        if (param.getTakeoffLat() != null) mission.setTakeoffLat(param.getTakeoffLat());
        if (param.getTakeoffLng() != null) mission.setTakeoffLng(param.getTakeoffLng());
        if (param.getTakeoffAlt() != null) mission.setTakeoffAlt(param.getTakeoffAlt());
        if (param.getWindSpeed() != null) mission.setWindSpeedAtApproval(param.getWindSpeed());
        if (param.getWindDirectionDeg() != null) mission.setWindDirectionDeg(param.getWindDirectionDeg());
    }

    private static Map<String, Object> buildApprovePayload(MissionApproveParam param) {
        Map<String, Object> payload = new HashMap<>();
        if (param.getAircraftSn() != null) payload.put("aircraftSn", param.getAircraftSn());
        if (param.getPayloadId() != null) payload.put("payloadId", param.getPayloadId());
        if (param.getWaterLoadLiters() != null) payload.put("waterLoadLiters", param.getWaterLoadLiters());
        if (param.getTakeoffLat() != null) payload.put("takeoffLat", param.getTakeoffLat());
        if (param.getTakeoffLng() != null) payload.put("takeoffLng", param.getTakeoffLng());
        if (param.getTakeoffAlt() != null) payload.put("takeoffAlt", param.getTakeoffAlt());
        if (param.getWindSpeed() != null) payload.put("windSpeed", param.getWindSpeed());
        if (param.getWindDirectionDeg() != null) payload.put("windDirectionDeg", param.getWindDirectionDeg());
        return payload;
    }
}
