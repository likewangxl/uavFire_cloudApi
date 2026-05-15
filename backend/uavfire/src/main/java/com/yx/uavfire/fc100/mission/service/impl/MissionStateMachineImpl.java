package com.yx.uavfire.fc100.mission.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.mission.dao.FireMissionLogMapper;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionLogEntity;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus;
import com.yx.uavfire.fc100.mission.service.MissionStateMachine;
import com.yx.uavfire.fc100.mission.service.TransitCommand;
import com.yx.uavfire.fc100.mission.statemachine.TransitionTable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
public class MissionStateMachineImpl implements MissionStateMachine {

    private final FireMissionMapper missionMapper;
    private final FireMissionLogMapper logMapper;
    private final Clock clock;

    public MissionStateMachineImpl(FireMissionMapper m, FireMissionLogMapper l, Clock c) {
        this.missionMapper = m;
        this.logMapper = l;
        this.clock = c;
    }

    @Override
    @Transactional
    public FireMissionEntity transit(TransitCommand cmd) {
        FireMissionEntity m = missionMapper.selectOne(
            new QueryWrapper<FireMissionEntity>()
                .eq("mission_no", cmd.getMissionNo())
                .eq("deleted", 0));
        if (m == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.MISSION_NOT_FOUND, cmd.getMissionNo());
        }

        FireMissionStatus from = FireMissionStatus.valueOf(m.getStatus());
        if (cmd.getExpectedFrom() != null && cmd.getExpectedFrom() != from) {
            throw new Fc100BusinessException(Fc100ErrorCode.STATUS_TRANSITION_FORBIDDEN,
                "expected " + cmd.getExpectedFrom() + " but actual " + from);
        }

        Optional<FireMissionStatus> nextOpt = TransitionTable.nextStatus(from, cmd.getEvent());
        if (nextOpt.isEmpty()) {
            throw new Fc100BusinessException(Fc100ErrorCode.STATUS_TRANSITION_FORBIDDEN,
                "event " + cmd.getEvent() + " not allowed from " + from);
        }
        FireMissionStatus to = nextOpt.get();
        long now = clock.now();

        UpdateWrapper<FireMissionEntity> w = new UpdateWrapper<>();
        w.set("status", to.name())
         .set("version", m.getVersion() + 1)
         .set("update_time", now)
         .set("updated_by", cmd.getOperatorId());
        applyEventSideEffects(cmd.getEvent(), w, now, cmd);
        w.eq("mission_no", cmd.getMissionNo())
         .eq("status", from.name())
         .eq("deleted", 0);
        if (cmd.getExpectedVersion() != null) {
            w.eq("version", cmd.getExpectedVersion());
        } else {
            w.eq("version", m.getVersion());
        }

        int rows = missionMapper.update(null, w);
        if (rows == 0) {
            throw new Fc100BusinessException(Fc100ErrorCode.VERSION_MISMATCH,
                "concurrent modification on " + cmd.getMissionNo() + "; please refresh");
        }

        FireMissionLogEntity logEntity = new FireMissionLogEntity();
        logEntity.setMissionId(m.getId());
        logEntity.setAction(cmd.getEvent().name());
        logEntity.setFromStatus(from.name());
        logEntity.setToStatus(to.name());
        logEntity.setOperatorId(cmd.getOperatorId());
        logEntity.setOperatorRole(cmd.getOperatorRole());
        logEntity.setClientIp(cmd.getClientIp());
        logEntity.setRequestId(cmd.getRequestId());
        logEntity.setIdempotencyKey(cmd.getIdempotencyKey());
        logEntity.setRemark(cmd.getRemark());
        logEntity.setCreateTime(now);
        logMapper.insert(logEntity);

        log.info("transit mission={} {} → {} via {}",
            cmd.getMissionNo(), from, to, cmd.getEvent());

        return missionMapper.selectOne(
            new QueryWrapper<FireMissionEntity>().eq("mission_no", cmd.getMissionNo()));
    }

    /** 各事件在 mission 表上的附加副作用列。 */
    private void applyEventSideEffects(FireMissionEvent e, UpdateWrapper<FireMissionEntity> w,
                                        long now, TransitCommand cmd) {
        java.util.Map<String, Object> p = cmd.getPayload();
        switch (e) {
            case APPROVE: {
                w.set("approved_at", now).set("approver_id", cmd.getOperatorId());
                if (p.get("aircraftSn") != null)         w.set("aircraft_sn", p.get("aircraftSn"));
                if (p.get("payloadId") != null)          w.set("payload_id", p.get("payloadId"));
                if (p.get("waterLoadLiters") != null)    w.set("water_load_liters", p.get("waterLoadLiters"));
                if (p.get("windSpeed") != null)          w.set("wind_speed_at_approval", p.get("windSpeed"));
                if (p.get("windDirectionDeg") != null)   w.set("wind_direction_deg", p.get("windDirectionDeg"));
                if (p.get("takeoffLat") != null)         w.set("takeoff_lat", p.get("takeoffLat"));
                if (p.get("takeoffLng") != null)         w.set("takeoff_lng", p.get("takeoffLng"));
                if (p.get("takeoffAlt") != null)         w.set("takeoff_alt", p.get("takeoffAlt"));
                break;
            }
            case START_DELIVERY:    w.set("started_at", now); break;
            case CONFIRM_RELEASE:
                w.set("payload_released_at", now);
                w.set("release_operator_id", cmd.getOperatorId());
                break;
            case SUBMIT_REVIEW:
                w.set("completed_at", now);
                w.set("reviewer_id", cmd.getOperatorId());
                break;
            case ARCHIVE:           w.set("archived_at", now); break;
            case REJECT:
            case FORCE_FAIL:
            case RESOLVE_TAKEOVER_FAILED:
            case MARK_RELEASE_FAILED:
            case MARK_RETURN_FAILED:
                if (p.get("reason") != null) w.set("failed_reason", p.get("reason"));
                break;
            default:
                // 无副作用
                break;
        }
    }

    @Override
    public Set<FireMissionEvent> allowedEvents(FireMissionStatus current) {
        return TransitionTable.allowedEvents(current);
    }
}
