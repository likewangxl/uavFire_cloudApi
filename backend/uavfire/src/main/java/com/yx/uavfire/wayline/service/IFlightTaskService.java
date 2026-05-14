package com.yx.uavfire.wayline.service;

import com.yx.uavfire.common.model.CustomClaim;
import com.yx.uavfire.wayline.model.dto.ConditionalWaylineJobKey;
import com.yx.uavfire.wayline.model.dto.WaylineJobDTO;
import com.yx.uavfire.wayline.model.param.CreateJobParam;
import com.yx.uavfire.wayline.model.param.UpdateJobParam;
import com.dji.sdk.common.HttpResultResponse;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

/**
 * @author sean
 * @version 1.1
 * @date 2022/6/9
 */
public interface IFlightTaskService {


    /**
     * Issue wayline mission to the dock.
     * @param param
     * @param customClaim   user info
     * @return
     */
    HttpResultResponse publishFlightTask(CreateJobParam param, CustomClaim customClaim) throws SQLException;

    /**
     * Issue wayline mission to the dock.
     * @param waylineJob
     * @return
     * @throws SQLException
     */
    HttpResultResponse publishOneFlightTask(WaylineJobDTO waylineJob) throws SQLException;

    /**
     * Execute the task immediately.
     * @param jobId
     * @throws SQLException
     * @return
     */
    Boolean executeFlightTask(String workspaceId, String jobId);

    /**
     * Cancel the task Base on job Ids.
     * @param workspaceId
     * @param jobIds
     * @throws SQLException
     */
    void cancelFlightTask(String workspaceId, Collection<String> jobIds);

    /**
     * Cancel the dock tasks that have been issued but have not yet been executed.
     * @param workspaceId
     * @param dockSn
     * @param jobIds
     */
    void publishCancelTask(String workspaceId, String dockSn, List<String> jobIds);

    /**
     * Set the media files for this job to upload immediately.
     * @param workspaceId
     * @param jobId
     */
    void uploadMediaHighestPriority(String workspaceId, String jobId);

    /**
     * Manually control the execution status of wayline job.
     * @param workspaceId
     * @param jobId
     * @param param
     */
    void updateJobStatus(String workspaceId, String jobId, UpdateJobParam param);

    void retryPrepareJob(ConditionalWaylineJobKey jobKey, WaylineJobDTO waylineJob);
}
