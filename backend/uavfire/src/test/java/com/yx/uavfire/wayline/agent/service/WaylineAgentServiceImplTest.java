package com.yx.uavfire.wayline.agent.service;

import com.yx.uavfire.wayline.agent.model.dto.WaylineAgentCommandAckDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineAgentCommandDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineControlDataDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineDispatchDataDTO;
import com.yx.uavfire.wayline.agent.model.enums.WaylineAgentMethodEnum;
import com.yx.uavfire.wayline.agent.service.impl.WaylineAgentServiceImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class WaylineAgentServiceImplTest {

    @Test
    void dispatchWayline_enqueuesCommandWithExpectedShape() {
        WaylineAgentServiceImpl svc = new WaylineAgentServiceImpl();
        WaylineDispatchDataDTO data = new WaylineDispatchDataDTO()
                .setMissionId("m-1")
                .setKmzUrl("http://x/y.kmz")
                .setKmzMd5("md5-abc");

        WaylineAgentCommandDTO issued = svc.dispatchWayline("SN-A", data);

        assertNotNull(issued.getTid());
        assertEquals("m-1", issued.getBid());
        assertEquals(WaylineAgentMethodEnum.WAYLINE_DISPATCH.getMethod(), issued.getMethod());
        assertSame(data, issued.getData());
    }

    @Test
    void pollCommand_returnsLastDispatchedCommand() {
        WaylineAgentServiceImpl svc = new WaylineAgentServiceImpl();
        WaylineAgentCommandDTO issued = svc.dispatchWayline("SN-A", new WaylineDispatchDataDTO().setMissionId("m-1"));

        WaylineAgentCommandDTO polled = svc.pollCommand("SN-A");

        assertSame(issued, polled);
    }

    @Test
    void pollCommand_returnsNullWhenNoCommandPending() {
        WaylineAgentServiceImpl svc = new WaylineAgentServiceImpl();
        assertNull(svc.pollCommand("SN-A"));
    }

    @Test
    void pollCommand_isolatesPerDrone() {
        WaylineAgentServiceImpl svc = new WaylineAgentServiceImpl();
        WaylineAgentCommandDTO forA = svc.dispatchWayline("SN-A", new WaylineDispatchDataDTO().setMissionId("m-1"));

        assertSame(forA, svc.pollCommand("SN-A"));
        assertNull(svc.pollCommand("SN-B"));
    }

    @Test
    void acknowledgeCommand_clearsQueueWhenTidMatches() {
        WaylineAgentServiceImpl svc = new WaylineAgentServiceImpl();
        WaylineAgentCommandDTO issued = svc.dispatchWayline("SN-A", new WaylineDispatchDataDTO().setMissionId("m-1"));

        svc.acknowledgeCommand("SN-A", new WaylineAgentCommandAckDTO().setTid(issued.getTid()).setResult(0));

        assertNull(svc.pollCommand("SN-A"));
    }

    @Test
    void acknowledgeCommand_doesNothingWhenTidMismatches() {
        WaylineAgentServiceImpl svc = new WaylineAgentServiceImpl();
        WaylineAgentCommandDTO issued = svc.dispatchWayline("SN-A", new WaylineDispatchDataDTO().setMissionId("m-1"));

        svc.acknowledgeCommand("SN-A", new WaylineAgentCommandAckDTO().setTid("wrong-tid").setResult(0));

        assertSame(issued, svc.pollCommand("SN-A"));
    }

    @Test
    void acknowledgeCommand_doesNothingWhenNoCommandPending() {
        WaylineAgentServiceImpl svc = new WaylineAgentServiceImpl();
        // no NPE
        svc.acknowledgeCommand("SN-A", new WaylineAgentCommandAckDTO().setTid("x").setResult(0));
        assertNull(svc.pollCommand("SN-A"));
    }

    @Test
    void secondDispatch_overwritesFirstUnacknowledged() {
        WaylineAgentServiceImpl svc = new WaylineAgentServiceImpl();
        WaylineAgentCommandDTO first = svc.dispatchWayline("SN-A", new WaylineDispatchDataDTO().setMissionId("m-1"));
        WaylineAgentCommandDTO second = svc.dispatchWayline("SN-A", new WaylineDispatchDataDTO().setMissionId("m-2"));

        assertNotEquals(first.getTid(), second.getTid());
        assertSame(second, svc.pollCommand("SN-A"));
    }

    @Test
    void pauseResumeStopQueryBreakpoint_eachEnqueueOwnMethod() {
        WaylineAgentServiceImpl svc = new WaylineAgentServiceImpl();
        WaylineControlDataDTO ctrl = new WaylineControlDataDTO().setMissionId("m-1");

        assertEquals(WaylineAgentMethodEnum.WAYLINE_PAUSE.getMethod(),
                svc.pauseMission("SN-A", ctrl).getMethod());
        svc.acknowledgeCommand("SN-A", new WaylineAgentCommandAckDTO().setTid(svc.pollCommand("SN-A").getTid()).setResult(0));

        assertEquals(WaylineAgentMethodEnum.WAYLINE_RESUME.getMethod(),
                svc.resumeMission("SN-A", ctrl).getMethod());
        svc.acknowledgeCommand("SN-A", new WaylineAgentCommandAckDTO().setTid(svc.pollCommand("SN-A").getTid()).setResult(0));

        assertEquals(WaylineAgentMethodEnum.WAYLINE_STOP.getMethod(),
                svc.stopMission("SN-A", ctrl).getMethod());
        svc.acknowledgeCommand("SN-A", new WaylineAgentCommandAckDTO().setTid(svc.pollCommand("SN-A").getTid()).setResult(0));

        assertEquals(WaylineAgentMethodEnum.WAYLINE_QUERY_BREAKPOINT.getMethod(),
                svc.queryBreakpoint("SN-A", ctrl).getMethod());
    }
}
