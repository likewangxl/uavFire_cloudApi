package com.yx.uavfire.wayline.agent.controller;

import com.dji.sdk.common.HttpResultResponse;
import com.yx.uavfire.wayline.agent.model.WaylineEventRecord;
import com.yx.uavfire.wayline.agent.service.WaylineEventStore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("${url.wayline-agent.prefix}${url.wayline-agent.version}")
@RequiredArgsConstructor
public class WaylineAgentEventController {

    private final WaylineEventStore eventStore;

    @GetMapping("/missions/{mission_id}/events")
    public HttpResultResponse<List<WaylineEventRecord>> listEvents(@PathVariable("mission_id") String missionId) {
        return HttpResultResponse.success(eventStore.getByMission(missionId));
    }
}
