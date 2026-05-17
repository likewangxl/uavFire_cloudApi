package com.yx.uavfire.wayline.agent.controller;

import com.dji.sdk.common.HttpResultResponse;
import com.yx.uavfire.wayline.agent.model.dto.WaylineAgentCommandAckDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineAgentCommandDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineControlDataDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineDispatchDataDTO;
import com.yx.uavfire.wayline.agent.service.IWaylineAgentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${url.wayline-agent.prefix}${url.wayline-agent.version}")
public class WaylineAgentController {

    @Autowired
    private IWaylineAgentService waylineAgentService;

    @GetMapping("/agents/{drone_sn}/command")
    public HttpResultResponse<WaylineAgentCommandDTO> pollCommand(@PathVariable("drone_sn") String droneSn) {
        return HttpResultResponse.success(waylineAgentService.pollCommand(droneSn));
    }

    @PostMapping("/agents/{drone_sn}/command/ack")
    public HttpResultResponse<Void> acknowledgeCommand(@PathVariable("drone_sn") String droneSn,
                                                       @RequestBody WaylineAgentCommandAckDTO body) {
        waylineAgentService.acknowledgeCommand(droneSn, body);
        return HttpResultResponse.success();
    }

    @PostMapping("/agents/{drone_sn}/dispatch")
    public HttpResultResponse<WaylineAgentCommandDTO> dispatch(@PathVariable("drone_sn") String droneSn,
                                                               @RequestBody WaylineDispatchDataDTO body) {
        return HttpResultResponse.success(waylineAgentService.dispatchWayline(droneSn, body));
    }

    @PostMapping("/agents/{drone_sn}/pause")
    public HttpResultResponse<WaylineAgentCommandDTO> pause(@PathVariable("drone_sn") String droneSn,
                                                            @RequestBody WaylineControlDataDTO body) {
        return HttpResultResponse.success(waylineAgentService.pauseMission(droneSn, body));
    }

    @PostMapping("/agents/{drone_sn}/resume")
    public HttpResultResponse<WaylineAgentCommandDTO> resume(@PathVariable("drone_sn") String droneSn,
                                                             @RequestBody WaylineControlDataDTO body) {
        return HttpResultResponse.success(waylineAgentService.resumeMission(droneSn, body));
    }

    @PostMapping("/agents/{drone_sn}/stop")
    public HttpResultResponse<WaylineAgentCommandDTO> stop(@PathVariable("drone_sn") String droneSn,
                                                           @RequestBody WaylineControlDataDTO body) {
        return HttpResultResponse.success(waylineAgentService.stopMission(droneSn, body));
    }
}
