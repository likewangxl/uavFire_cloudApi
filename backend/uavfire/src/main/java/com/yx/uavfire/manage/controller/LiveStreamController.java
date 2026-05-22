package com.yx.uavfire.manage.controller;

import com.yx.uavfire.common.model.CustomClaim;
import com.yx.uavfire.manage.model.dto.CapacityDeviceDTO;
import com.yx.uavfire.manage.model.dto.LiveTypeDTO;
import com.yx.uavfire.manage.service.ILiveStreamService;
import com.dji.sdk.common.HttpResultResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

import static com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM;

/**
 * @author sean.zhou
 * @version 0.1
 * @date 2021/11/19
 *
 * NOTE (2026-05-21, MSDK Migration Phase 1):
 * Cloud SDK livestream path is being superseded by the MSDK Agent dual-stream
 * pipeline (see docs/MSDK_MIGRATION_PLAN.md). This controller is kept as a
 * fallback during phase 1 so we can roll back if the agent path breaks in
 * production. Phase 2 will delete this controller and its service impls
 * after the agent path has been validated on real hardware.
 *
 * Do not add new functionality here — extend the DualStream agent pipeline
 * instead.
 */

@RestController
@Slf4j
@Deprecated
@RequestMapping("${url.manage.prefix}${url.manage.version}/live")
public class LiveStreamController {

    @Autowired
    private ILiveStreamService liveStreamService;

    @Autowired
    private ObjectMapper mapper;

    /**
     * Get live capability data of all drones in the current user's workspace from the database.
     * @param request
     * @return  live capability
     */
    @GetMapping("/capacity")
    public HttpResultResponse<List<CapacityDeviceDTO>> getLiveCapacity(HttpServletRequest request) {
        // Get information about the current user.
        CustomClaim customClaim = (CustomClaim)request.getAttribute(TOKEN_CLAIM);

        List<CapacityDeviceDTO> liveCapacity = liveStreamService.getLiveCapacity(customClaim.getWorkspaceId());

        return HttpResultResponse.success(liveCapacity);
    }

    /**
     * Live streaming according to the parameters passed in from the web side.
     * @param liveParam Live streaming parameters.
     * @return
     */
    @PostMapping("/streams/start")
    public HttpResultResponse liveStart(@RequestBody LiveTypeDTO liveParam) {
        return liveStreamService.liveStart(liveParam);
    }

    /**
     * Stop live streaming according to the parameters passed in from the web side.
     * @param liveParam Live streaming parameters.
     * @return
     */
    @PostMapping("/streams/stop")
    public HttpResultResponse liveStop(@RequestBody LiveTypeDTO liveParam) {
        return liveStreamService.liveStop(liveParam.getVideoId());
    }

    /**
     * Set the quality of the live streaming according to the parameters passed in from the web side.
     * @param liveParam Live streaming parameters.
     * @return
     */
    @PostMapping("/streams/update")
    public HttpResultResponse liveSetQuality(@RequestBody LiveTypeDTO liveParam) {
        return liveStreamService.liveSetQuality(liveParam);
    }

    @PostMapping("/streams/switch")
    public HttpResultResponse liveLensChange(@RequestBody LiveTypeDTO liveParam) {
        return liveStreamService.liveLensChange(liveParam);
    }

}
