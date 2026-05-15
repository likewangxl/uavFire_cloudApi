package com.yx.uavfire.fc100.route.controller;

import com.yx.uavfire.fc100.common.ApiResult;
import com.yx.uavfire.fc100.common.idempotency.Idempotent;
import com.yx.uavfire.fc100.route.model.dto.RouteFileDTO;
import com.yx.uavfire.fc100.route.service.RouteExportService;
import lombok.Data;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.util.Map;

@RestController
@RequestMapping("/api/fire/missions/{no}/route")
public class RouteController {

    private final RouteExportService service;

    public RouteController(RouteExportService s) {
        this.service = s;
    }

    @Data
    public static class ExportParam {
        @NotBlank private String operatorId;
    }

    @PostMapping("/export-kmz")
    @Idempotent("route.export-kmz")
    public ApiResult<RouteFileDTO> exportKmz(@PathVariable("no") String no,
                                              @Valid @RequestBody ExportParam p,
                                              HttpServletRequest req) {
        return ApiResult.success(service.exportKmz(no, p.getOperatorId(),
            req.getRemoteAddr(), req.getHeader("X-Request-Id")));
    }

    @GetMapping("/files/latest")
    public ApiResult<RouteFileDTO> latest(@PathVariable("no") String no) {
        return ApiResult.success(service.getLatest(no));
    }

    @GetMapping("/files/{fileId}/download")
    public ResponseEntity<byte[]> download(@PathVariable("no") String no,
                                            @PathVariable("fileId") Long fileId) {
        byte[] bytes = service.downloadById(fileId);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + no + ".kmz\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(bytes);
    }

    @GetMapping("/files/{fileId}/download-url")
    public ApiResult<Map<String, Object>> downloadUrl(@PathVariable("no") String no,
                                                       @PathVariable("fileId") Long fileId,
                                                       HttpServletRequest req) {
        RouteExportService.DownloadUrlResult result = service.getDownloadUrl(fileId, req.getContextPath(), no);
        return ApiResult.success(Map.of("url", result.url, "expiresInSec", result.expiresInSec));
    }
}
