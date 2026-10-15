package com.yx.uavfire.fc100.mission.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus;
import com.yx.uavfire.fc100.mission.model.param.MissionApproveParam;
import com.yx.uavfire.fc100.safety.check.FourEyesCheck;
import com.yx.uavfire.fc100.safety.check.WindSpeedCheck;
import com.yx.uavfire.fc100.safety.config.Fc100SafetyProperties;
import com.yx.uavfire.fc100.safety.service.impl.SafetyCheckServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MissionApprovalServiceTest {

    private final FireMissionMapper missionMapper = mock(FireMissionMapper.class);
    private final MissionStateMachine stateMachine = mock(MissionStateMachine.class);
    private final Fc100SafetyProperties properties = new Fc100SafetyProperties();
    private final MissionApprovalService service = new MissionApprovalService(
            missionMapper,
            new SafetyCheckServiceImpl(List.of(new FourEyesCheck(properties), new WindSpeedCheck(properties)), missionMapper),
            stateMachine);

    @Test
    void windOverThresholdDoesNotWriteApproved() {
        when(missionMapper.selectOne(any(Wrapper.class))).thenReturn(mission("commander-1"));

        Fc100BusinessException error = assertThrows(Fc100BusinessException.class,
                () -> service.approve("M-1", approve(12.0), "commander-2", "COMMANDER", null));

        assertEquals(Fc100ErrorCode.SAFETY_CHECK_FAILED, error.getErrorCode());
        verify(stateMachine, never()).transit(any());
        verify(missionMapper, never()).update(any(), any());
    }

    @Test
    void samePersonFailsFourEyesWithoutTransit() {
        assertTrue(properties.isFourEyesEnabled());
        when(missionMapper.selectOne(any(Wrapper.class))).thenReturn(mission("commander-1"));

        Fc100BusinessException error = assertThrows(Fc100BusinessException.class,
                () -> service.approve("M-1", approve(3.0), "commander-1", "COMMANDER", null));

        assertEquals(Fc100ErrorCode.SAFETY_CHECK_FAILED, error.getErrorCode());
        assertTrue(error.getMessage().contains("approver must differ"));
        verify(stateMachine, never()).transit(any());
    }

    @Test
    void differentApproverPassesChecksThenTransits() {
        FireMissionEntity current = mission("commander-1");
        when(missionMapper.selectOne(any(Wrapper.class))).thenReturn(current);
        when(stateMachine.transit(any())).thenReturn(current);

        service.approve("M-1", approve(3.0), "commander-2", "COMMANDER", null);

        ArgumentCaptor<TransitCommand> command = ArgumentCaptor.forClass(TransitCommand.class);
        verify(stateMachine).transit(command.capture());
        assertEquals(FireMissionEvent.APPROVE, command.getValue().getEvent());
        assertEquals(FireMissionStatus.WAITING_REVIEW, command.getValue().getExpectedFrom());
        assertEquals("commander-2", command.getValue().getOperatorId());
        assertEquals("COMMANDER", command.getValue().getOperatorRole());
        verify(missionMapper, never()).update(any(), any());
    }

    private static FireMissionEntity mission(String createdBy) {
        FireMissionEntity mission = new FireMissionEntity();
        mission.setMissionNo("M-1");
        mission.setStatus(FireMissionStatus.WAITING_REVIEW.name());
        mission.setCreatedBy(createdBy);
        mission.setDeleted(0);
        return mission;
    }

    private static MissionApproveParam approve(double wind) {
        MissionApproveParam param = new MissionApproveParam();
        param.setOperatorId("ignored-body");
        param.setWindSpeed(wind);
        param.setWindDirectionDeg(10.0);
        param.setTakeoffLat(30.0);
        param.setTakeoffLng(120.0);
        return param;
    }
}
