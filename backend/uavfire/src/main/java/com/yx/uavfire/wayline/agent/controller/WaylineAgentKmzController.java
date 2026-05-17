package com.yx.uavfire.wayline.agent.controller;

import com.yx.uavfire.wayline.agent.model.WaylineAgentKmzEntry;
import com.yx.uavfire.wayline.agent.service.IWaylineAgentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("${url.wayline-agent.prefix}${url.wayline-agent.version}")
public class WaylineAgentKmzController {

    public static final String HEADER_KMZ_MD5 = "X-Kmz-Md5";

    @Autowired
    private IWaylineAgentService waylineAgentService;

    @GetMapping("/agents/{drone_sn}/missions/{mission_id}/kmz")
    public ResponseEntity<byte[]> downloadKmz(@PathVariable("drone_sn") String droneSn,
                                              @PathVariable("mission_id") String missionId) {
        Optional<WaylineAgentKmzEntry> entry = waylineAgentService.getKmz(droneSn, missionId);
        if (entry.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        WaylineAgentKmzEntry kmz = entry.get();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(kmz.getKmzBytes().length)
                .header(HEADER_KMZ_MD5, kmz.getMd5())
                .body(kmz.getKmzBytes());
    }
}
