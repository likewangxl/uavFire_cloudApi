package com.yx.uavfire.miniapp.controller;

import com.yx.uavfire.common.model.CustomClaim;
import com.yx.uavfire.miniapp.service.MiniAppReadService;
import com.yx.uavfire.miniapp.web.MiniAppResponse;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.*;
import java.util.*;

@RestController
@RequestMapping("/miniapp/api/v1")
public class MiniAppBusinessController {
    private final MiniAppReadService reads;
    public MiniAppBusinessController(MiniAppReadService reads) { this.reads = reads; }
    private CustomClaim claim(HttpServletRequest request) { return MiniAppControllerSupport.requireClaim(request); }
    private <T> MiniAppResponse<T> ok(T data, HttpServletRequest request, HttpServletResponse response) { return MiniAppControllerSupport.success(data, request, response); }

    @GetMapping("/inspection-tasks")
    public MiniAppResponse<?> tasks(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int pageSize,
            @RequestParam(defaultValue="") String keyword, @RequestParam(defaultValue="") String status, HttpServletRequest request, HttpServletResponse response) {
        return ok(reads.tasks(claim(request), page, pageSize, keyword, status, false, false), request, response);
    }
    @GetMapping("/inspection-tasks/{id}")
    public MiniAppResponse<?> task(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) { return ok(reads.task(claim(request), id), request, response); }
    @GetMapping("/inspection-tasks/{id}/timeline")
    public MiniAppResponse<?> taskTimeline(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) { return ok(reads.task(claim(request), id).get("timeline"), request, response); }
    @GetMapping("/inspection-tasks/{id}/flight-report")
    public MiniAppResponse<?> flightReport(@PathVariable String id, @RequestParam(required=false) String runId,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="50") int pageSize,
            @RequestParam(required=false) Long snapshotRecordId, HttpServletRequest request, HttpServletResponse response) {
        return ok(reads.flightReport(claim(request),id,runId,page,pageSize,snapshotRecordId),request,response);
    }
    @GetMapping("/events")
    public MiniAppResponse<?> events(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int pageSize,
            @RequestParam(defaultValue="") String status, HttpServletRequest request, HttpServletResponse response) { return ok(reads.events(claim(request), page, pageSize, status), request, response); }
    @GetMapping("/inspection-tasks/{id}/flight-report/related")
    public MiniAppResponse<?> related(@PathVariable String id,@RequestParam String runId,@RequestParam String kind,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="100") int pageSize,@RequestParam(required=false) Long snapshotRecordId,
            HttpServletRequest request,HttpServletResponse response){return ok(reads.flightRelated(claim(request),id,runId,kind,page,pageSize,snapshotRecordId),request,response);}
    @GetMapping("/events/{id}")
    public MiniAppResponse<?> event(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) { return ok(reads.event(claim(request), id), request, response); }
    @GetMapping("/events/{id}/timeline")
    public MiniAppResponse<?> eventTimeline(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) { return ok(reads.event(claim(request), id).get("timeline"), request, response); }
    @GetMapping("/reports")
    public MiniAppResponse<?> reports(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int pageSize,
            @RequestParam(defaultValue="false") boolean savedOnly, HttpServletRequest request, HttpServletResponse response) { return ok(reads.tasks(claim(request), page, pageSize, "", "", true, savedOnly), request, response); }
    @GetMapping("/reports/{id}")
    public MiniAppResponse<?> report(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) { return ok(reads.report(claim(request), id), request, response); }
    @PutMapping("/reports/{id}/bookmark")
    public MiniAppResponse<?> bookmark(@PathVariable String id, @RequestBody Map<String, Boolean> body, HttpServletRequest request, HttpServletResponse response) {
        if (!body.containsKey("saved") || body.get("saved") == null) throw new com.yx.uavfire.miniapp.web.MiniAppException(org.springframework.http.HttpStatus.BAD_REQUEST, com.yx.uavfire.miniapp.web.MiniAppErrorCode.VALIDATION_ERROR, "请提供 saved 字段");
        reads.saveReport(claim(request), id, body.get("saved")); return ok(Map.of("saved", body.get("saved")), request, response);
    }
    @GetMapping("/devices")
    public MiniAppResponse<?> devices(HttpServletRequest request, HttpServletResponse response) { return ok(Map.of("items", reads.devices(claim(request))), request, response); }
    @GetMapping("/notification-preferences")
    public MiniAppResponse<?> preferences(HttpServletRequest request, HttpServletResponse response) { return ok(reads.preferences(claim(request)), request, response); }
    @PutMapping("/notification-preferences")
    public MiniAppResponse<?> preferences(@RequestBody Map<String, Boolean> body, HttpServletRequest request, HttpServletResponse response) { return ok(reads.savePreferences(claim(request), body), request, response); }
}
