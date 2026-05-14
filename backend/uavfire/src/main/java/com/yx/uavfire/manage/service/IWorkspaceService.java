package com.yx.uavfire.manage.service;


import com.yx.uavfire.manage.model.dto.WorkspaceDTO;

import java.util.Optional;

public interface IWorkspaceService {

    /**
     * Query the information of a workspace based on its workspace id.
     * @param workspaceId
     * @return
     */
    Optional<WorkspaceDTO> getWorkspaceByWorkspaceId(String workspaceId);

    /**
     * Query the workspace of a workspace based on bind code.
     * @param bindCode
     * @return
     */
    Optional<WorkspaceDTO> getWorkspaceNameByBindCode(String bindCode);

    /**
     * 获取默认工作空间信息。
     *
     * @return 默认工作空间
     */
    Optional<WorkspaceDTO> getDefaultWorkspace();

}
