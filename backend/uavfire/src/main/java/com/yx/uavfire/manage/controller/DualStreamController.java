package com.yx.uavfire.manage.controller;

import com.yx.uavfire.manage.model.dto.DualStreamAgentCapabilityDTO;
import com.yx.uavfire.manage.model.dto.DualStreamAgentHeartbeatDTO;
import com.yx.uavfire.manage.model.dto.DualStreamAgentStatusDTO;
import com.yx.uavfire.manage.model.dto.DualStreamCommandAckDTO;
import com.yx.uavfire.manage.model.dto.DualStreamCommandDTO;
import com.yx.uavfire.manage.model.dto.DualStreamEventDTO;
import com.yx.uavfire.manage.model.dto.DualStreamLiveGroupDTO;
import com.yx.uavfire.manage.model.dto.VisibleRoiSnapshotDTO;
import com.yx.uavfire.manage.service.IDualStreamService;
import com.dji.sdk.common.HttpResultResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("${url.manage.prefix}${url.manage.version}/dual-stream")
public class DualStreamController {

    @Autowired
    private IDualStreamService dualStreamService;

    @PostMapping("/agents/{drone_sn}/heartbeat")
    public HttpResultResponse<Void> heartbeat(@PathVariable("drone_sn") String droneSn,
                                              @RequestBody DualStreamAgentHeartbeatDTO body) {
        dualStreamService.acceptHeartbeat(droneSn, body);
        return HttpResultResponse.success();
    }

    @PostMapping("/agents/{drone_sn}/status")
    public HttpResultResponse<Void> status(@PathVariable("drone_sn") String droneSn,
                                           @RequestBody DualStreamAgentStatusDTO body) {
        dualStreamService.acceptStatus(droneSn, body);
        return HttpResultResponse.success();
    }

    @PostMapping("/agents/{drone_sn}/capability")
    public HttpResultResponse<Void> capability(@PathVariable("drone_sn") String droneSn,
                                               @RequestBody DualStreamAgentCapabilityDTO body) {
        dualStreamService.acceptCapability(droneSn, body);
        return HttpResultResponse.success();
    }

    @GetMapping("/agents/{drone_sn}/command")
    public HttpResultResponse<DualStreamCommandDTO> pollCommand(@PathVariable("drone_sn") String droneSn) {
        return HttpResultResponse.success(dualStreamService.pollCommand(droneSn));
    }

    @PostMapping("/agents/{drone_sn}/command/ack")
    public HttpResultResponse<Void> acknowledgeCommand(@PathVariable("drone_sn") String droneSn,
                                                       @RequestBody DualStreamCommandAckDTO body) {
        dualStreamService.acknowledgeCommand(droneSn, body);
        return HttpResultResponse.success();
    }

    @PostMapping("/tasks/{task_id}/events")
    public HttpResultResponse<Void> event(@PathVariable("task_id") String taskId,
                                          @RequestBody DualStreamEventDTO body) {
        dualStreamService.acceptEvent(taskId, body);
        return HttpResultResponse.success();
    }

    @GetMapping("/tasks/{task_id}/events")
    public HttpResultResponse<List<DualStreamEventDTO>> listEvents(@PathVariable("task_id") String taskId) {
        return HttpResultResponse.success(dualStreamService.listEvents(taskId));
    }

    @GetMapping("/tasks/{task_id}/latest-visible-roi")
    public HttpResultResponse<VisibleRoiSnapshotDTO> latestVisibleRoi(
            @PathVariable("task_id") String taskId,
            @RequestParam(name = "after_source_ts", defaultValue = "0") long afterSourceTs) {
        return HttpResultResponse.success(dualStreamService.latestVisibleRoi(taskId, afterSourceTs));
    }

    @GetMapping("/groups/{drone_sn}")
    public HttpResultResponse<DualStreamLiveGroupDTO> getGroup(@PathVariable("drone_sn") String droneSn) {
        return HttpResultResponse.success(dualStreamService.getGroup(droneSn));
    }

    @PostMapping("/groups/{drone_sn}/start")
    public HttpResultResponse<DualStreamCommandDTO> start(@PathVariable("drone_sn") String droneSn) {
        return HttpResultResponse.success(dualStreamService.issueCommand(droneSn, "start"));
    }

    @PostMapping("/groups/{drone_sn}/stop")
    public HttpResultResponse<DualStreamCommandDTO> stop(@PathVariable("drone_sn") String droneSn) {
        return HttpResultResponse.success(dualStreamService.issueCommand(droneSn, "stop"));
    }

    @PostMapping("/groups/{drone_sn}/focus")
    public HttpResultResponse<DualStreamCommandDTO> focus(@PathVariable("drone_sn") String droneSn,
                                                          @RequestBody(required = false) DualStreamCommandDTO body) {
        String action = body != null && StringUtils.hasText(body.getAction()) ? body.getAction() : "focus";
        return HttpResultResponse.success(dualStreamService.issueCommand(droneSn, action));
    }
}
