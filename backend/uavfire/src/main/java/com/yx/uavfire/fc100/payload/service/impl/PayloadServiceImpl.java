package com.yx.uavfire.fc100.payload.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent;
import com.yx.uavfire.fc100.mission.service.MissionStateMachine;
import com.yx.uavfire.fc100.mission.service.TransitCommand;
import com.yx.uavfire.fc100.payload.dao.PayloadEventMapper;
import com.yx.uavfire.fc100.payload.model.entity.PayloadEventEntity;
import com.yx.uavfire.fc100.payload.model.param.PayloadConfirmReleaseParam;
import com.yx.uavfire.fc100.payload.service.PayloadReleasePolicyService;
import com.yx.uavfire.fc100.payload.service.PayloadService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class PayloadServiceImpl implements PayloadService {

    private final FireMissionMapper missionMapper;
    private final PayloadEventMapper eventMapper;
    private final MissionStateMachine sm;
    private final ObjectMapper om;
    private final Clock clock;
    private final PayloadReleasePolicyService releasePolicyService;

    public PayloadServiceImpl(FireMissionMapper m, PayloadEventMapper p,
                               MissionStateMachine sm, ObjectMapper om, Clock c,
                               PayloadReleasePolicyService releasePolicyService) {
        this.missionMapper = m;
        this.eventMapper = p;
        this.sm = sm;
        this.om = om;
        this.clock = c;
        this.releasePolicyService = releasePolicyService;
    }

    @Override
    @Transactional
    public void markReleasePending(String missionNo, String operatorId, String clientIp, String requestId) {
        FireMissionEntity m = mustLoad(missionNo);
        sm.transit(TransitCommand.builder()
            .missionNo(missionNo).event(FireMissionEvent.MARK_RELEASE_PENDING)
            .operatorId(operatorId).clientIp(clientIp).requestId(requestId).build());
        writeEvent(m.getId(), "RELEASE_PENDING", operatorId, null, null);
    }

    @Override
    @Transactional
    public void confirmRelease(String missionNo, PayloadConfirmReleaseParam p,
                                String clientIp, String requestId) {
        FireMissionEntity m = mustLoad(missionNo);
        PayloadReleasePolicyService.ReleasePolicyDecision decision =
            releasePolicyService.validateReleaseRequest(m,
                PayloadReleasePolicyService.ReleaseRequest.builder()
                    .operatorId(p == null ? null : p.getOperatorId())
                    .confirmedRelease(isPayloadChecklistConfirmed(p))
                    .clientIp(clientIp)
                    .requestId(requestId)
                    .build());

        String checklistJson;
        try {
            checklistJson = om.writeValueAsString(p == null ? null : p.getChecklistTimestamps());
        } catch (Exception e) {
            throw new Fc100BusinessException(Fc100ErrorCode.INTERNAL_ERROR,
                "checklist json: " + e.getMessage());
        }
        if (decision.isDryRun()) {
            writeEvent(m.getId(), "RELEASE_DRY_RUN", p == null ? null : p.getOperatorId(), "DRY_RUN", checklistJson);
            return;
        }
        writeEvent(m.getId(), "RELEASED", p.getOperatorId(), null, checklistJson);
        sm.transit(TransitCommand.builder()
            .missionNo(missionNo).event(FireMissionEvent.CONFIRM_RELEASE)
            .operatorId(p.getOperatorId())
            .clientIp(clientIp).requestId(requestId).build());
    }

    @Override
    @Transactional
    public void markReleaseFailed(String missionNo, String operatorId, String reason,
                                    String clientIp, String requestId) {
        FireMissionEntity m = mustLoad(missionNo);
        sm.transit(TransitCommand.builder()
            .missionNo(missionNo).event(FireMissionEvent.MARK_RELEASE_FAILED)
            .operatorId(operatorId)
            .payload(Map.of("reason", reason != null ? reason : ""))
            .clientIp(clientIp).requestId(requestId).build());
        writeEvent(m.getId(), "RELEASE_FAILED", operatorId, reason, null);
    }

    @Override
    @Transactional
    public void retryRelease(String missionNo, String operatorId, String clientIp, String requestId) {
        sm.transit(TransitCommand.builder()
            .missionNo(missionNo).event(FireMissionEvent.RETRY_RELEASE)
            .operatorId(operatorId).clientIp(clientIp).requestId(requestId).build());
    }

    private FireMissionEntity mustLoad(String missionNo) {
        FireMissionEntity m = missionMapper.selectOne(
            new QueryWrapper<FireMissionEntity>().eq("mission_no", missionNo).eq("deleted", 0));
        if (m == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.MISSION_NOT_FOUND, missionNo);
        }
        return m;
    }

    private void writeEvent(Long missionId, String type, String operatorId,
                             String value, String checklistJson) {
        PayloadEventEntity e = new PayloadEventEntity();
        e.setMissionId(missionId);
        e.setEventType(type);
        e.setOperatorId(operatorId);
        e.setEventValue(value);
        if (checklistJson != null && !checklistJson.equals("null")) {
            e.setPreReleaseChecklist(checklistJson);
        }
        e.setCreateTime(clock.now());
        eventMapper.insert(e);
    }

    private boolean isPayloadChecklistConfirmed(PayloadConfirmReleaseParam p) {
        return p != null
            && Boolean.TRUE.equals(p.getConfirmedArrival())
            && Boolean.TRUE.equals(p.getConfirmedNoPeopleRisk())
            && Boolean.TRUE.equals(p.getConfirmedWindOk())
            && Boolean.TRUE.equals(p.getConfirmedPayloadReady())
            && Boolean.TRUE.equals(p.getConfirmedRelease());
    }
}
