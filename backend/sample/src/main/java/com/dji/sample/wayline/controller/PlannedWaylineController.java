package com.dji.sample.wayline.controller;

import com.dji.sample.common.model.CustomClaim;
import com.dji.sample.wayline.model.dto.PlannedWaylineDTO;
import com.dji.sample.wayline.model.param.CreatePlannedWaylineParam;
import com.dji.sample.wayline.model.param.PublishPlannedWaylineResponse;
import com.dji.sample.wayline.model.param.UpdatePlannedWaylineParam;
import com.dji.sample.wayline.service.IPlannedWaylineService;
import com.dji.sdk.common.HttpResultResponse;
import com.dji.sdk.common.PaginationData;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.Objects;

import static com.dji.sample.component.AuthInterceptor.TOKEN_CLAIM;

@RestController
@RequiredArgsConstructor
@RequestMapping("${url.wayline.prefix}${url.wayline.version}/workspaces")
public class PlannedWaylineController {

    private final IPlannedWaylineService plannedWaylineService;

    @GetMapping("/{workspace_id}/planned-waylines")
    public HttpResultResponse<PaginationData<PlannedWaylineDTO>> list(HttpServletRequest request,
                                                                     @PathVariable("workspace_id") String workspaceId,
                                                                     @RequestParam(defaultValue = "1") Long page,
                                                                     @RequestParam(name = "page_size", defaultValue = "10") Long pageSize) {
        String trustedWorkspaceId = resolveWorkspaceId(workspaceId, resolveClaim(request));
        return HttpResultResponse.success(plannedWaylineService.getByWorkspace(trustedWorkspaceId, page, pageSize));
    }

    @PostMapping("/{workspace_id}/planned-waylines")
    public HttpResultResponse<PlannedWaylineDTO> create(HttpServletRequest request,
                                                        @PathVariable("workspace_id") String workspaceId,
                                                        @Valid @RequestBody CreatePlannedWaylineParam param) {
        CustomClaim customClaim = resolveClaim(request);
        String trustedWorkspaceId = resolveWorkspaceId(workspaceId, customClaim);
        return HttpResultResponse.success(plannedWaylineService.create(trustedWorkspaceId, customClaim.getUsername(), param));
    }

    @PutMapping("/{workspace_id}/planned-waylines/{id}")
    public HttpResultResponse<PlannedWaylineDTO> update(@PathVariable("workspace_id") String workspaceId,
                                                        @PathVariable("id") String id,
                                                        @Valid @RequestBody UpdatePlannedWaylineParam param,
                                                        HttpServletRequest request) {
        String trustedWorkspaceId = resolveWorkspaceId(workspaceId, resolveClaim(request));
        PlannedWaylineDTO dto = plannedWaylineService.update(trustedWorkspaceId, id, param);
        return HttpResultResponse.success(dto);
    }

    @PostMapping("/{workspace_id}/planned-waylines/{id}/publish")
    public HttpResultResponse<PublishPlannedWaylineResponse> publish(@PathVariable("workspace_id") String workspaceId,
                                                                     @PathVariable("id") String id,
                                                                     HttpServletRequest request) {
        String trustedWorkspaceId = resolveWorkspaceId(workspaceId, resolveClaim(request));
        return HttpResultResponse.success(plannedWaylineService.publish(trustedWorkspaceId, id));
    }

    @DeleteMapping("/{workspace_id}/planned-waylines/{id}")
    public HttpResultResponse<Void> delete(@PathVariable("workspace_id") String workspaceId,
                                           @PathVariable("id") String id,
                                           HttpServletRequest request) {
        String trustedWorkspaceId = resolveWorkspaceId(workspaceId, resolveClaim(request));
        plannedWaylineService.delete(trustedWorkspaceId, id);
        return HttpResultResponse.success();
    }

    private CustomClaim resolveClaim(HttpServletRequest request) {
        CustomClaim customClaim = (CustomClaim) request.getAttribute(TOKEN_CLAIM);
        if (Objects.isNull(customClaim)) {
            throw new IllegalArgumentException("Workspace mismatch.");
        }
        return customClaim;
    }

    private String resolveWorkspaceId(String workspaceId, CustomClaim customClaim) {
        if (Objects.isNull(customClaim) || !Objects.equals(workspaceId, customClaim.getWorkspaceId())) {
            throw new IllegalArgumentException("Workspace mismatch.");
        }
        return customClaim.getWorkspaceId();
    }

}
