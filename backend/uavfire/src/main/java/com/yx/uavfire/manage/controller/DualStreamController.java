package com.yx.uavfire.manage.controller;

import com.yx.uavfire.manage.model.dto.DualStreamAgentCapabilityDTO;
import com.yx.uavfire.manage.model.dto.AgentFireEventReceiptDTO;
import com.yx.uavfire.manage.model.dto.DualStreamAgentHeartbeatDTO;
import com.yx.uavfire.manage.model.dto.DualStreamAgentStatusDTO;
import com.yx.uavfire.manage.model.dto.DualStreamCommandAckDTO;
import com.yx.uavfire.manage.model.dto.DualStreamCommandDTO;
import com.yx.uavfire.manage.model.dto.DualStreamEventDTO;
import com.yx.uavfire.manage.model.dto.DualStreamLiveGroupDTO;
import com.yx.uavfire.manage.model.dto.VisibleRoiSnapshotDTO;
import com.yx.uavfire.manage.model.dto.AgentFireEvidenceReceiptDTO;
import com.yx.uavfire.manage.service.IDualStreamService;
import com.yx.uavfire.manage.service.AgentFireEvidenceService;
import com.yx.uavfire.wayline.agent.security.WaylineAgentAuthInterceptor;
import com.yx.uavfire.wayline.agent.security.WaylineAgentClaim;
import com.dji.sdk.common.HttpResultResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("${url.manage.prefix}${url.manage.version}/dual-stream")
public class DualStreamController {

    @Autowired
    private IDualStreamService dualStreamService;

    @Autowired
    private AgentFireEvidenceService agentFireEvidenceService;

    @Autowired(required = false)
    private com.yx.uavfire.video.VideoBandwidthService videoBandwidthService;

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

    @PostMapping("/tasks/{task_id}/agent-fire-events")
    public HttpResultResponse<AgentFireEventReceiptDTO> agentFireEvent(
            @PathVariable("task_id") String taskId,
            @RequestBody DualStreamEventDTO body,
            HttpServletRequest request) {
        WaylineAgentClaim claim = requireAgentClaim(request);
        if (body == null
                || !claim.getDroneSn().equals(body.getDroneSn())
                || !("fire-" + claim.getDroneSn()).equals(taskId)
                || !taskId.equals(body.getTaskId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "agent task/device mismatch");
        }
        if (!agentFireEvidenceService.verify(
                body.getDroneSn(),
                body.getEventId(),
                body.getVisibleImageUrl(),
                body.getEvidenceSha256(),
                body.getEvidenceCapturedAt())) {
            return HttpResultResponse.success(new AgentFireEventReceiptDTO()
                    .setEventId(body.getEventId())
                    .setStatus("rejected")
                    .setReason("evidence-not-verified"));
        }
        body.setEvidenceStatus("VERIFIED");
        AgentFireEventReceiptDTO receipt = dualStreamService.acceptAgentFireEvent(taskId, body);
        if (videoBandwidthService != null && receipt != null && "accepted".equals(receipt.getStatus())) {
            videoBandwidthService.prioritizeVerifiedFire(body.getDroneSn());
        }
        return HttpResultResponse.success(receipt);
    }

    @PostMapping(value = "/agents/{drone_sn}/fire-evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public HttpResultResponse<AgentFireEvidenceReceiptDTO> uploadFireEvidence(
            @PathVariable("drone_sn") String droneSn,
            @RequestParam("event_id") String eventId,
            @RequestParam("sha256") String sha256,
            @RequestParam("captured_at") long capturedAt,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest request) throws IOException {
        WaylineAgentClaim claim = requireAgentClaim(request);
        if (!claim.getDroneSn().equals(droneSn)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "agent device mismatch");
        }
        try {
            return HttpResultResponse.success(
                    agentFireEvidenceService.store(droneSn, eventId, sha256, capturedAt, file));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    @GetMapping(value = "/fire-evidence/{drone_sn}/{filename:.+}", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<Resource> fireEvidence(
            @PathVariable("drone_sn") String droneSn,
            @PathVariable("filename") String filename) throws IOException {
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic())
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(agentFireEvidenceService.load(droneSn, filename));
        } catch (IllegalArgumentException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "evidence not found");
        }
    }

    private WaylineAgentClaim requireAgentClaim(HttpServletRequest request) {
        Object claim = request.getAttribute(WaylineAgentAuthInterceptor.ATTR_CLAIM);
        if (!(claim instanceof WaylineAgentClaim)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "agent claim missing");
        }
        return (WaylineAgentClaim) claim;
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
