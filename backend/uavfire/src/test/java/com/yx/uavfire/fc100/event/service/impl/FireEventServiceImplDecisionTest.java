package com.yx.uavfire.fc100.event.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.MissionNoGenerator;
import com.yx.uavfire.fc100.event.dao.FireEventHistoryMapper;
import com.yx.uavfire.fc100.event.dao.FireEventMapper;
import com.yx.uavfire.fc100.event.model.dto.FireEventDecisionResult;
import com.yx.uavfire.fc100.event.model.dto.FireEventRecheckResultDTO;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.event.model.entity.FireEventHistoryEntity;
import com.yx.uavfire.fc100.event.model.param.FireEventActionParam;
import com.yx.uavfire.fc100.event.model.param.FireEventRecheckResultParam;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus;
import com.yx.uavfire.fc100.operation.dao.OperationIncidentMapper;
import com.yx.uavfire.fc100.operation.model.dto.OperationIncidentDTO;
import com.yx.uavfire.fc100.operation.model.entity.OperationIncidentEntity;
import com.yx.uavfire.fc100.operation.model.enums.OperationIncidentEvent;
import com.yx.uavfire.fc100.operation.model.enums.OperationIncidentStatus;
import com.yx.uavfire.fc100.operation.model.param.CreateOperationIncidentParam;
import com.yx.uavfire.fc100.operation.model.param.OperationActionParam;
import com.yx.uavfire.fc100.operation.service.IncidentStateMachine;
import com.yx.uavfire.fc100.operation.service.IncidentTransitCommand;
import com.yx.uavfire.fc100.operation.service.OperationIncidentService;
import com.yx.uavfire.manage.service.IDeviceRedisService;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FireEventServiceImplDecisionTest {

    @Test
    void confirmPreciseFireEventCreatesIncidentAndDraftMissionOnce() {
        Fixture f = fixture();
        FireEventEntity event = event("PRECISE");
        when(f.events.selectOne(any(Wrapper.class))).thenReturn(event);
        when(f.incidents.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(f.operationService.create(any(CreateOperationIncidentParam.class))).thenReturn(incidentDto(501L));
        when(f.missions.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(f.missions.insert(any(FireMissionEntity.class))).thenAnswer(inv -> {
            FireMissionEntity mission = inv.getArgument(0);
            mission.setId(701L);
            return 1;
        });

        FireEventDecisionResult result = f.service.confirm("10", action("commander-1"), request());

        assertEquals("CONFIRMED", result.getFireEvent().getConfirmedStatus());
        assertEquals(501L, result.getIncident().getId());
        assertTrue(result.isDraftMissionCreated());
        assertFalse(result.isReusedIncident());
        verify(f.operationService).create(argThat(param ->
            Long.valueOf(10L).equals(param.getFireEventId())
                && "commander-1".equals(param.getConfirmedBy())));
        verify(f.missions).insert(argThat(mission ->
            Long.valueOf(10L).equals(mission.getFireEventId())
                && Long.valueOf(501L).equals(mission.getIncidentId())
                && FireMissionStatus.CREATED.name().equals(mission.getStatus())));
        verify(f.histories).insert(argThat(history ->
            "CONFIRMED".equals(history.getAction())
                && "commander-1".equals(history.getSourceEventId())));
    }

    @Test
    void repeatedConfirmReusesActiveIncidentAndDoesNotCreateSecondDraft() {
        Fixture f = fixture();
        FireEventEntity event = event("PRECISE");
        event.setLinkedIncidentId(501L);
        OperationIncidentEntity existing = incident(501L, OperationIncidentStatus.CONFIRMED);
        when(f.events.selectOne(any(Wrapper.class))).thenReturn(event);
        when(f.incidents.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
        FireMissionEntity mission = new FireMissionEntity();
        mission.setMissionNo("M-EXISTING");
        mission.setStatus(FireMissionStatus.CREATED.name());
        when(f.missions.selectList(any(Wrapper.class))).thenReturn(List.of(mission));

        FireEventDecisionResult result = f.service.confirm("10", action("commander-1"), request());

        assertTrue(result.isReusedIncident());
        assertFalse(result.isDraftMissionCreated());
        assertEquals("M-EXISTING", result.getDraftMissionNo());
        verify(f.operationService, never()).create(any(CreateOperationIncidentParam.class));
        verify(f.missions, never()).insert(any(FireMissionEntity.class));
    }

    @Test
    void confirmEstimatedFireEventDoesNotCreateDraftAndMarksRecheckRecommendation() {
        Fixture f = fixture();
        FireEventEntity event = event("ESTIMATED");
        when(f.events.selectOne(any(Wrapper.class))).thenReturn(event);
        when(f.incidents.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(f.operationService.create(any(CreateOperationIncidentParam.class))).thenReturn(incidentDto(501L));
        when(f.incidents.updateById(any(OperationIncidentEntity.class))).thenReturn(1);

        FireEventDecisionResult result = f.service.confirm("10", action("commander-2"), request());

        assertFalse(result.isDraftMissionCreated());
        assertNull(result.getDraftMissionNo());
        assertTrue(result.isRecommendedRecheck());
        assertTrue(result.getRecheckReason().contains("需复测或人工标注坐标"));
        verify(f.missions, never()).insert(any(FireMissionEntity.class));
        verify(f.incidents).updateById(argThat(incident ->
            Long.valueOf(501L).equals(incident.getId())
                && Integer.valueOf(1).equals(incident.getRecommendedRecheck())
                && incident.getRecheckReason().contains("需复测或人工标注坐标")));
    }

    @Test
    void rejectPendingEventMarksRejectedAndFalseAlarmsLinkedCandidateIncident() {
        Fixture f = fixture();
        FireEventEntity event = event("PRECISE");
        event.setLinkedIncidentId(501L);
        when(f.events.selectOne(any(Wrapper.class))).thenReturn(event);
        when(f.incidents.selectById(501L)).thenReturn(incident(501L, OperationIncidentStatus.CANDIDATE));
        when(f.operationService.markFalseAlarm(any(), any(OperationActionParam.class), any(HttpServletRequest.class)))
            .thenReturn(incident(501L, OperationIncidentStatus.FALSE_ALARM));

        FireEventDecisionResult result = f.service.reject("10", action("commander-3"), request());

        assertEquals("REJECTED", result.getFireEvent().getConfirmedStatus());
        assertEquals(OperationIncidentStatus.FALSE_ALARM.name(), result.getIncident().getStatus());
        verify(f.operationService).markFalseAlarm(argThat(id -> id.equals(501L)), argThat(param ->
            "commander-3".equals(param.getOperatorId())
                && param.getReason().contains("fire event rejected")), any(HttpServletRequest.class));
        verify(f.histories).insert(argThat(history -> "REJECTED".equals(history.getAction())));
    }

    @Test
    void saturatedRecheckCannotResolveWithTemperatureDropOnlyWhenAreaDoesNotDrop() {
        Fixture f = fixture();
        FireEventEntity event = event("PRECISE");
        event.setThermalTemperature(545.0);
        event.setThermalRoi("{\"areaM2\":10.0}");
        event.setLinkedIncidentId(501L);
        when(f.events.selectOne(any(Wrapper.class))).thenReturn(event);
        when(f.incidents.selectById(501L)).thenReturn(incident(501L, OperationIncidentStatus.RECHECKING));
        when(f.stateMachine.transit(any(IncidentTransitCommand.class)))
            .thenReturn(incident(501L, OperationIncidentStatus.RESPONDING));

        FireEventRecheckResultParam param = recheck("RESOLVED", 430.0, 9.5, false);
        FireEventRecheckResultDTO result = f.service.recordRecheckResult("10", param, request());

        assertFalse(result.isResolved());
        assertTrue(result.getReason().contains("测温可能饱和"));
        verify(f.stateMachine).transit(argThat(cmd ->
            cmd.getEvent() == OperationIncidentEvent.CONTINUE_RESPONSE
                && "commander-4".equals(cmd.getOperatorId())));
    }

    @Test
    void recheckResultMovesRecheckingIncidentToResolvedOrResponding() {
        Fixture f = fixture();
        FireEventEntity event = event("PRECISE");
        event.setThermalTemperature(120.0);
        event.setThermalRoi("{\"areaM2\":10.0}");
        event.setLinkedIncidentId(501L);
        when(f.events.selectOne(any(Wrapper.class))).thenReturn(event);
        when(f.incidents.selectById(501L)).thenReturn(incident(501L, OperationIncidentStatus.RECHECKING));
        when(f.stateMachine.transit(any(IncidentTransitCommand.class)))
            .thenReturn(incident(501L, OperationIncidentStatus.RESOLVED));

        FireEventRecheckResultDTO resolved = f.service.recordRecheckResult("10",
            recheck("RESOLVED", 48.0, 0.2, false), request());

        assertTrue(resolved.isResolved());
        verify(f.stateMachine).transit(argThat(cmd -> cmd.getEvent() == OperationIncidentEvent.RESOLVE));

        when(f.stateMachine.transit(any(IncidentTransitCommand.class)))
            .thenReturn(incident(501L, OperationIncidentStatus.RESPONDING));
        FireEventRecheckResultDTO responding = f.service.recordRecheckResult("10",
            recheck("CONTINUE_RESPONSE", 160.0, 14.0, true), request());

        assertFalse(responding.isResolved());
        verify(f.stateMachine).transit(argThat(cmd -> cmd.getEvent() == OperationIncidentEvent.CONTINUE_RESPONSE));
    }

    private Fixture fixture() {
        FireEventMapper events = mock(FireEventMapper.class);
        FireEventHistoryMapper histories = mock(FireEventHistoryMapper.class);
        FireMissionMapper missions = mock(FireMissionMapper.class);
        MissionNoGenerator noGen = mock(MissionNoGenerator.class);
        Clock clock = mock(Clock.class);
        IDeviceRedisService redis = mock(IDeviceRedisService.class);
        OperationIncidentService operationService = mock(OperationIncidentService.class);
        OperationIncidentMapper incidents = mock(OperationIncidentMapper.class);
        IncidentStateMachine stateMachine = mock(IncidentStateMachine.class);
        Fc100ThermalProperties thermal = new Fc100ThermalProperties();
        thermal.setSaturationTempC(540.0);
        when(clock.now()).thenReturn(1770000000000L);
        when(noGen.next()).thenReturn("M-DRAFT-001");
        FireEventServiceImpl service = new FireEventServiceImpl(
            events, histories, missions, noGen, clock, redis, null,
            operationService, incidents, stateMachine, thermal);
        return new Fixture(events, histories, missions, operationService, incidents, stateMachine, service);
    }

    private FireEventEntity event(String quality) {
        FireEventEntity event = new FireEventEntity();
        event.setId(10L);
        event.setEventId("fire-10");
        event.setWorkspaceId("DEFAULT");
        event.setSource("M4T");
        event.setDeviceSn("M4T-1");
        event.setConfidence(new BigDecimal("0.92"));
        event.setFireLevel("HIGH");
        event.setLat(34.1);
        event.setLng(109.1);
        event.setGeoQuality(quality);
        event.setGeoErrorRadiusM(6.0);
        event.setConfirmedStatus("PENDING");
        event.setStatus("CANDIDATE");
        event.setDeleted(0);
        return event;
    }

    private OperationIncidentDTO incidentDto(Long id) {
        OperationIncidentDTO dto = new OperationIncidentDTO();
        dto.setId(id);
        dto.setIncidentNo("INCIDENT-1");
        dto.setFireEventId(10L);
        dto.setStatus(OperationIncidentStatus.CONFIRMED.name());
        return dto;
    }

    private OperationIncidentEntity incident(Long id, OperationIncidentStatus status) {
        OperationIncidentEntity incident = new OperationIncidentEntity();
        incident.setId(id);
        incident.setIncidentNo("INCIDENT-1");
        incident.setFireEventId(10L);
        incident.setStatus(status.name());
        return incident;
    }

    private FireEventActionParam action(String operatorId) {
        FireEventActionParam param = new FireEventActionParam();
        param.setOperatorId(operatorId);
        return param;
    }

    private FireEventRecheckResultParam recheck(String suggestion, double maxTemp, double hotAreaM2, boolean flameVisible) {
        FireEventRecheckResultParam param = new FireEventRecheckResultParam();
        param.setOperatorId("commander-4");
        param.setSuggestion(suggestion);
        param.setMaxTemp(maxTemp);
        param.setHotAreaM2(hotAreaM2);
        param.setFlameVisible(flameVisible);
        return param;
    }

    private HttpServletRequest request() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("X-Request-Id")).thenReturn("REQ-1");
        when(request.getHeader("X-Idempotency-Key")).thenReturn("IDEMP-1");
        return request;
    }

    private static class Fixture {
        final FireEventMapper events;
        final FireEventHistoryMapper histories;
        final FireMissionMapper missions;
        final OperationIncidentService operationService;
        final OperationIncidentMapper incidents;
        final IncidentStateMachine stateMachine;
        final FireEventServiceImpl service;

        Fixture(FireEventMapper events, FireEventHistoryMapper histories, FireMissionMapper missions,
                OperationIncidentService operationService, OperationIncidentMapper incidents,
                IncidentStateMachine stateMachine, FireEventServiceImpl service) {
            this.events = events;
            this.histories = histories;
            this.missions = missions;
            this.operationService = operationService;
            this.incidents = incidents;
            this.stateMachine = stateMachine;
            this.service = service;
        }
    }
}
