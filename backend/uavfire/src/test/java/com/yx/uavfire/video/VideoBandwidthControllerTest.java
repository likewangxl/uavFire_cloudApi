package com.yx.uavfire.video;

import com.yx.uavfire.common.model.CustomClaim;
import com.yx.uavfire.component.AuthInterceptor;
import com.yx.uavfire.manage.model.dto.DeviceDTO;
import com.yx.uavfire.manage.service.IDeviceService;
import com.yx.uavfire.wayline.agent.security.WaylineAgentAuthInterceptor;
import com.yx.uavfire.wayline.agent.security.WaylineAgentClaim;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.util.concurrent.atomic.AtomicLong;

class VideoBandwidthControllerTest {
    @Test void httpContractUsesSnakeCaseAndIdentityBoundDecision() throws Exception {
        VideoBandwidthProperties props = new VideoBandwidthProperties();
        props.setEnabled(true); props.setAircraftSns(List.of("A"));
        AtomicLong now = new AtomicLong();
        VideoBandwidthService service = new VideoBandwidthService(props, now::get);
        now.set(26_000);
        var mvc = MockMvcBuilders.standaloneSetup(new VideoBandwidthController(service, mock(IDeviceService.class)))
                .addPlaceholderValue("url.manage.prefix", "/manage/api")
                .addPlaceholderValue("url.manage.version", "/v1").build();
        mvc.perform(post("/manage/api/v1/dual-stream/agents/A/video-policy")
                .requestAttr(WaylineAgentAuthInterceptor.ATTR_CLAIM, new WaylineAgentClaim("A", WaylineAgentClaim.ROLE))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"protocol_version\":1,\"instance_id\":\"instance-001\",\"streaming\":true,\"applied_profile\":\"LOW\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.drone_sn").value("A"))
                .andExpect(jsonPath("$.data.profile").value("HIGH"))
                .andExpect(jsonPath("$.data.bitrate_bps").value(4_000_000))
                .andExpect(jsonPath("$.data.valid_for_ms").value(15_000));
    }
    @Test void policyRequiresMatchingAgentClaim() {
        VideoBandwidthService service = mock(VideoBandwidthService.class);
        VideoBandwidthController c = new VideoBandwidthController(service, mock(IDeviceService.class));
        MockHttpServletRequest req = new MockHttpServletRequest();
        assertThrows(ResponseStatusException.class, () -> c.policy("A", new VideoPolicyReport(), req));
        req.setAttribute(WaylineAgentAuthInterceptor.ATTR_CLAIM, new WaylineAgentClaim("B", WaylineAgentClaim.ROLE));
        assertThrows(ResponseStatusException.class, () -> c.policy("A", new VideoPolicyReport(), req));
        verifyNoInteractions(service);
    }
    @Test void viewerCannotPrioritizeAnotherWorkspace() {
        VideoBandwidthProperties props = new VideoBandwidthProperties(); props.setAircraftSns(List.of("A"));
        VideoBandwidthService service = new VideoBandwidthService(props, () -> 0L);
        IDeviceService devices = mock(IDeviceService.class);
        when(devices.getDeviceBySn("A")).thenReturn(Optional.of(DeviceDTO.builder().deviceSn("A").workspaceId("other").build()));
        VideoBandwidthController c = new VideoBandwidthController(service, devices);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(AuthInterceptor.TOKEN_CLAIM, new CustomClaim("user", "name", 1, "mine"));
        VideoBandwidthController.ViewerRequest body = new VideoBandwidthController.ViewerRequest(); body.setDroneSn("A");
        assertThrows(ResponseStatusException.class, () -> c.view("page-00001", body, req));
        body.setDroneSn(null);
        assertDoesNotThrow(() -> c.view("page-00001", body, req));
    }
}
