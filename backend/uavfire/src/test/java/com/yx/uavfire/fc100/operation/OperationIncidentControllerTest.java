package com.yx.uavfire.fc100.operation;

import com.yx.uavfire.fc100.common.ApiResult;
import com.yx.uavfire.fc100.common.idempotency.Idempotent;
import com.yx.uavfire.fc100.operation.controller.OperationIncidentController;
import com.yx.uavfire.fc100.operation.model.entity.OperationIncidentEntity;
import com.yx.uavfire.fc100.operation.model.enums.OperationIncidentStatus;
import com.yx.uavfire.fc100.operation.model.param.OperationActionParam;
import com.yx.uavfire.fc100.operation.service.OperationIncidentService;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationIncidentControllerTest {

    @Test
    void dispatchDelegatesToServiceAndReturnsApiResult() {
        OperationIncidentService service = mock(OperationIncidentService.class);
        OperationIncidentController controller = new OperationIncidentController(service);
        HttpServletRequest request = mock(HttpServletRequest.class);
        OperationActionParam param = new OperationActionParam();
        param.setOperatorId("commander-1");
        OperationIncidentEntity dispatching = new OperationIncidentEntity();
        dispatching.setId(501L);
        dispatching.setStatus(OperationIncidentStatus.DISPATCHING.name());
        when(service.dispatch(501L, param, request)).thenReturn(dispatching);

        ApiResult<OperationIncidentEntity> result = controller.dispatch(501L, param, request);

        assertEquals(0, result.getCode());
        assertEquals(OperationIncidentStatus.DISPATCHING.name(), result.getData().getStatus());
        verify(service).dispatch(501L, param, request);
    }

    @Test
    void abortDelegatesToServiceAndReturnsAbortedIncident() {
        OperationIncidentService service = mock(OperationIncidentService.class);
        OperationIncidentController controller = new OperationIncidentController(service);
        HttpServletRequest request = mock(HttpServletRequest.class);
        OperationActionParam param = new OperationActionParam();
        param.setOperatorId("commander-1");
        param.setReason("weather unsafe");
        OperationIncidentEntity aborted = new OperationIncidentEntity();
        aborted.setId(501L);
        aborted.setStatus(OperationIncidentStatus.ABORTED.name());
        when(service.abort(501L, param, request)).thenReturn(aborted);

        ApiResult<OperationIncidentEntity> result = controller.abort(501L, param, request);

        assertEquals(0, result.getCode());
        assertEquals(OperationIncidentStatus.ABORTED.name(), result.getData().getStatus());
        verify(service).abort(501L, param, request);
    }

    @Test
    void markFalseAlarmDelegatesToServiceAndReturnsFalseAlarmIncident() {
        OperationIncidentService service = mock(OperationIncidentService.class);
        OperationIncidentController controller = new OperationIncidentController(service);
        HttpServletRequest request = mock(HttpServletRequest.class);
        OperationActionParam param = new OperationActionParam();
        param.setOperatorId("commander-1");
        param.setReason("thermal source misread");
        OperationIncidentEntity falseAlarm = new OperationIncidentEntity();
        falseAlarm.setId(501L);
        falseAlarm.setStatus(OperationIncidentStatus.FALSE_ALARM.name());
        when(service.markFalseAlarm(501L, param, request)).thenReturn(falseAlarm);

        ApiResult<OperationIncidentEntity> result = controller.markFalseAlarm(501L, param, request);

        assertEquals(0, result.getCode());
        assertEquals(OperationIncidentStatus.FALSE_ALARM.name(), result.getData().getStatus());
        verify(service).markFalseAlarm(501L, param, request);
    }

    @Test
    void dangerousStateEndpointsAreIdempotent() throws Exception {
        assertIdempotent("dispatch", "operation.incident.dispatch");
        assertIdempotent("abort", "operation.incident.abort");
        assertIdempotent("markFalseAlarm", "operation.incident.mark-false-alarm");
        assertIdempotent("close", "operation.incident.close");
    }

    private void assertIdempotent(String methodName, String key) throws Exception {
        Method method = OperationIncidentController.class.getMethod(
            methodName, Long.class, OperationActionParam.class, HttpServletRequest.class);
        Idempotent idempotent = method.getAnnotation(Idempotent.class);
        assertNotNull(idempotent);
        assertEquals(key, idempotent.value());
    }
}
