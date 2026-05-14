package com.yx.uavfire.manage.controller;

import com.yx.uavfire.common.model.CustomClaim;
import com.yx.uavfire.manage.model.dto.WorkspaceDTO;
import com.yx.uavfire.manage.service.IWorkspaceService;
import com.dji.sdk.common.HttpResultResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Optional;

import static com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM;

/**
 * @author sean.zhou
 * @version 0.1
 * @date 2021/11/23
 */
@RestController
@RequestMapping("${url.manage.prefix}${url.manage.version}/workspaces")
public class WorkspaceController {

    @Autowired
    private IWorkspaceService workspaceService;

    /**
     * Gets information about the workspace that the current user is in.
     * @param request
     * @return
     */
    @GetMapping("/current")
    public HttpResultResponse getCurrentWorkspace(HttpServletRequest request) {
        CustomClaim customClaim = (CustomClaim)request.getAttribute(TOKEN_CLAIM);
        Optional<WorkspaceDTO> workspaceOpt = workspaceService.getWorkspaceByWorkspaceId(customClaim.getWorkspaceId());

        return workspaceOpt.isEmpty() ? HttpResultResponse.error() : HttpResultResponse.success(workspaceOpt.get());
    }
}