package com.yx.uavfire.fc100.payload.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.mission.dao.FireMissionLogMapper;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionLogEntity;
import com.yx.uavfire.fc100.mission.model.enums.ReleaseExecutionMode;
import com.yx.uavfire.fc100.mission.model.enums.ReleasePolicy;
import com.yx.uavfire.fc100.mission.service.MissionStateMachine;
import com.yx.uavfire.fc100.mission.service.TransitCommand;
import com.yx.uavfire.fc100.payload.dao.PayloadEventMapper;
import com.yx.uavfire.fc100.payload.model.entity.PayloadEventEntity;
import com.yx.uavfire.fc100.payload.model.param.PayloadConfirmReleaseParam;
import com.yx.uavfire.fc100.payload.service.PayloadReleasePolicyService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayloadServiceImplReleasePolicyTest {

    private final FireMissionMapper missionMapper = mock(FireMissionMapper.class);
    private final PayloadEventMapper payloadEventMapper = mock(PayloadEventMapper.class);
    private final MissionStateMachine stateMachine = mock(MissionStateMachine.class);
    private final FireMissionLogMapper missionLogMapper = mock(FireMissionLogMapper.class);
    private final Clock clock = mock(Clock.class);

    private PayloadServiceImpl service() {
        when(clock.now()).thenReturn(1779163440000L);
        when(missionMapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);
        PayloadReleasePolicyService releasePolicyService =
            new PayloadReleasePolicyService(missionLogMapper, clock);
        return new PayloadServiceImpl(
            missionMapper,
            payloadEventMapper,
            stateMachine,
            new ObjectMapper(),
            clock,
            releasePolicyService);
    }

    @Test
    void confirmReleaseRejectsManualPolicyWithoutConfirmationAndWritesAuditLog() {
        FireMissionEntity mission = mission(ReleasePolicy.MANUAL_CONFIRM);
        when(missionMapper.selectOne(any(QueryWrapper.class))).thenReturn(mission);

        PayloadConfirmReleaseParam param = new PayloadConfirmReleaseParam();
        param.setOperatorId("operator-1");

        Fc100BusinessException ex = assertThrows(
            Fc100BusinessException.class,
            () -> service().confirmRelease("M-POLICY-001", param, "127.0.0.1", "REQ-001"));

        assertEquals(Fc100ErrorCode.RELEASE_CONFIRMATION_REQUIRED, ex.getErrorCode());
        verify(payloadEventMapper, never()).insert(any(PayloadEventEntity.class));
        verify(stateMachine, never()).transit(any(TransitCommand.class));

        ArgumentCaptor<FireMissionLogEntity> logCaptor = ArgumentCaptor.forClass(FireMissionLogEntity.class);
        verify(missionLogMapper).insert(logCaptor.capture());
        assertEquals(11L, logCaptor.getValue().getMissionId());
        assertEquals("PAYLOAD_RELEASE_TOKEN_DENIED", logCaptor.getValue().getAction());
        assertEquals("operator-1", logCaptor.getValue().getOperatorId());
        assertEquals("RELEASE_CONFIRMATION_TOKEN_REQUIRED", logCaptor.getValue().getRemark());
    }

    @Test
    void confirmReleaseRejectsControlledTestAutoWhenSwitchIsDisabled() {
        FireMissionEntity mission = mission(ReleasePolicy.CONTROLLED_TEST_AUTO);
        when(missionMapper.selectOne(any(QueryWrapper.class))).thenReturn(mission);

        PayloadConfirmReleaseParam param = confirmedParam();

        Fc100BusinessException ex = assertThrows(
            Fc100BusinessException.class,
            () -> service().confirmRelease("M-POLICY-002", param, "127.0.0.1", "REQ-002"));

        assertEquals(Fc100ErrorCode.CONTROLLED_TEST_AUTO_DISABLED, ex.getErrorCode());
        verify(payloadEventMapper, never()).insert(any(PayloadEventEntity.class));
        verify(stateMachine, never()).transit(any(TransitCommand.class));
        verify(missionLogMapper).insert(any(FireMissionLogEntity.class));
    }

    @Test
    void confirmReleaseRejectsNonPendingMissionAndWritesAuditLog() {
        FireMissionEntity mission = mission(ReleasePolicy.MANUAL_CONFIRM);
        mission.setStatus("IN_PROGRESS");
        when(missionMapper.selectOne(any(QueryWrapper.class))).thenReturn(mission);

        Fc100BusinessException ex = assertThrows(
            Fc100BusinessException.class,
            () -> service().confirmRelease("M-POLICY-003", confirmedParam("TOKEN-OK"), "127.0.0.1", "REQ-003"));

        assertEquals(Fc100ErrorCode.STATUS_TRANSITION_FORBIDDEN, ex.getErrorCode());
        verify(payloadEventMapper, never()).insert(any(PayloadEventEntity.class));
        verify(stateMachine, never()).transit(any(TransitCommand.class));

        ArgumentCaptor<FireMissionLogEntity> logCaptor = ArgumentCaptor.forClass(FireMissionLogEntity.class);
        verify(missionLogMapper).insert(logCaptor.capture());
        assertEquals("PAYLOAD_RELEASE_STATUS_DENIED", logCaptor.getValue().getAction());
        assertEquals("release requires PAYLOAD_RELEASE_PENDING", logCaptor.getValue().getRemark());
    }

    @Test
    void confirmReleaseRejectsMissingWrongExpiredAndReplayedToken() {
        assertReleaseTokenDenied(null, "RELEASE_CONFIRMATION_TOKEN_REQUIRED");
        assertReleaseTokenDenied("WRONG", "RELEASE_CONFIRMATION_TOKEN_MISMATCH");

        FireMissionEntity expired = missionWithToken("TOKEN-OK");
        expired.setReleaseTokenExpiresAt(1779163439999L);
        assertReleaseTokenDenied(expired, "TOKEN-OK", "RELEASE_CONFIRMATION_TOKEN_EXPIRED");

        FireMissionEntity replayed = missionWithToken("TOKEN-OK");
        replayed.setReleaseTokenUsedAt(1779163430000L);
        assertReleaseTokenDenied(replayed, "TOKEN-OK", "RELEASE_CONFIRMATION_TOKEN_REPLAYED");
    }

    @Test
    void confirmReleaseWithValidTokenRecordsOfficialManualEvidenceAndConsumesToken() {
        FireMissionEntity mission = missionWithToken("TOKEN-OK");
        when(missionMapper.selectOne(any(QueryWrapper.class))).thenReturn(mission);

        PayloadConfirmReleaseParam param = confirmedParam("TOKEN-OK");
        param.setRemoteHookRemark("pilot opened FC100 hook on official remote controller");

        service().confirmRelease("M-POLICY-004", param, "127.0.0.1", "REQ-004");

        ArgumentCaptor<PayloadEventEntity> eventCaptor = ArgumentCaptor.forClass(PayloadEventEntity.class);
        verify(payloadEventMapper).insert(eventCaptor.capture());
        assertEquals("RELEASED", eventCaptor.getValue().getEventType());
        assertEquals("OFFICIAL_HOOK_MANUAL:pilot opened FC100 hook on official remote controller",
            eventCaptor.getValue().getEventValue());
        assertEquals("operator-1", eventCaptor.getValue().getOperatorId());

        verify(missionMapper).update(any(), any(UpdateWrapper.class));
        verify(stateMachine).transit(any(TransitCommand.class));
    }

    private void assertReleaseTokenDenied(String submittedToken, String expectedRemark) {
        assertReleaseTokenDenied(missionWithToken("TOKEN-OK"), submittedToken, expectedRemark);
    }

    private void assertReleaseTokenDenied(FireMissionEntity mission, String submittedToken, String expectedRemark) {
        clearInvocations(payloadEventMapper, stateMachine, missionLogMapper);
        when(missionMapper.selectOne(any(QueryWrapper.class))).thenReturn(mission);

        Fc100BusinessException ex = assertThrows(
            Fc100BusinessException.class,
            () -> service().confirmRelease("M-POLICY-004", confirmedParam(submittedToken), "127.0.0.1", "REQ-004"));

        assertEquals(Fc100ErrorCode.RELEASE_CONFIRMATION_REQUIRED, ex.getErrorCode());
        verify(payloadEventMapper, never()).insert(any(PayloadEventEntity.class));
        verify(stateMachine, never()).transit(any(TransitCommand.class));

        ArgumentCaptor<FireMissionLogEntity> logCaptor = ArgumentCaptor.forClass(FireMissionLogEntity.class);
        verify(missionLogMapper).insert(logCaptor.capture());
        assertEquals("PAYLOAD_RELEASE_TOKEN_DENIED", logCaptor.getValue().getAction());
        assertEquals(expectedRemark, logCaptor.getValue().getRemark());
    }

    private FireMissionEntity mission(ReleasePolicy policy) {
        FireMissionEntity mission = new FireMissionEntity();
        mission.setId(11L);
        mission.setMissionNo("M-POLICY-001");
        mission.setStatus("PAYLOAD_RELEASE_PENDING");
        mission.setReleasePolicy(policy.name());
        mission.setReleaseExecutionMode(ReleaseExecutionMode.OFFICIAL_HOOK_MANUAL.name());
        mission.setReleaseConfirmationToken("TOKEN-OK");
        mission.setReleasePendingStartedAt(1779163140000L);
        mission.setReleaseTokenExpiresAt(1779163740000L);
        return mission;
    }

    private PayloadConfirmReleaseParam confirmedParam() {
        return confirmedParam("TOKEN-OK");
    }

    private PayloadConfirmReleaseParam confirmedParam(String confirmationToken) {
        PayloadConfirmReleaseParam param = new PayloadConfirmReleaseParam();
        param.setOperatorId("operator-1");
        param.setConfirmedArrival(true);
        param.setConfirmedNoPeopleRisk(true);
        param.setConfirmedWindOk(true);
        param.setConfirmedPayloadReady(true);
        param.setConfirmedRelease(true);
        param.setConfirmationToken(confirmationToken);
        return param;
    }

    private FireMissionEntity missionWithToken(String token) {
        FireMissionEntity mission = mission(ReleasePolicy.MANUAL_CONFIRM);
        mission.setReleaseConfirmationToken(token);
        mission.setReleasePendingStartedAt(1779163140000L);
        mission.setReleaseTokenExpiresAt(1779163740000L);
        return mission;
    }
}
