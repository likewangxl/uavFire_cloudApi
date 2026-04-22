package com.dji.sample.wayline.controller;

import com.dji.sample.common.model.CustomClaim;
import com.dji.sample.wayline.model.dto.PlannedWaylineDTO;
import com.dji.sample.wayline.model.param.CreatePlannedWaylineParam;
import com.dji.sample.wayline.model.param.UpdatePlannedWaylineParam;
import com.dji.sample.wayline.service.IPlannedWaylineService;
import com.dji.sdk.common.HttpResultResponse;
import com.dji.sdk.common.PaginationData;
import org.springframework.beans.factory.annotation.Autowired;
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

import static com.dji.sample.component.AuthInterceptor.TOKEN_CLAIM;

@RestController
@RequestMapping("${url.wayline.prefix}${url.wayline.version}/workspaces")
public class PlannedWaylineController {

    @Autowired
    private IPlannedWaylineService plannedWaylineService;

    @GetMapping("/{workspace_id}/planned-waylines")
    public HttpResultResponse<PaginationData<PlannedWaylineDTO>> list(@PathVariable("workspace_id") String workspaceId,
                                                                     @RequestParam(defaultValue = "1") Long page,
                                                                     @RequestParam(name = "page_size", defaultValue = "10") Long pageSize) {
        return HttpResultResponse.success(plannedWaylineService.getByWorkspace(workspaceId, page, pageSize));
    }

    @PostMapping("/{workspace_id}/planned-waylines")
    public HttpResultResponse<PlannedWaylineDTO> create(HttpServletRequest request,
                                                        @PathVariable("workspace_id") String workspaceId,
                                                        @Valid @RequestBody CreatePlannedWaylineParam param) {
        CustomClaim customClaim = (CustomClaim) request.getAttribute(TOKEN_CLAIM);
        return HttpResultResponse.success(plannedWaylineService.create(workspaceId, customClaim.getUsername(), param));
    }

    @PutMapping("/{workspace_id}/planned-waylines/{id}")
    public HttpResultResponse<PlannedWaylineDTO> update(@PathVariable("workspace_id") String workspaceId,
                                                        @PathVariable("id") String id,
                                                        @Valid @RequestBody UpdatePlannedWaylineParam param) {
        PlannedWaylineDTO dto = plannedWaylineService.update(workspaceId, id, param);
        return dto == null ? HttpResultResponse.error() : HttpResultResponse.success(dto);
    }

    @DeleteMapping("/{workspace_id}/planned-waylines/{id}")
    public HttpResultResponse<Void> delete(@PathVariable("workspace_id") String workspaceId,
                                           @PathVariable("id") String id) {
        plannedWaylineService.delete(workspaceId, id);
        return HttpResultResponse.success();
    }
}
