package com.yx.uavfire.manage.service;

import com.yx.uavfire.manage.model.dto.CapacityDeviceDTO;
import com.yx.uavfire.manage.model.dto.LiveTypeDTO;
import com.dji.sdk.cloudapi.device.VideoId;
import com.dji.sdk.common.HttpResultResponse;

import java.util.List;

/**
 * @author sean.zhou
 * @date 2021/11/19
 * @version 0.1
 *
 * NOTE (2026-05-21, MSDK Migration Phase 1): Cloud SDK livestream service.
 * Deprecated; superseded by DualStream agent pipeline. Kept as fallback
 * during phase 1. See docs/MSDK_MIGRATION_PLAN.md.
 */
@Deprecated
public interface ILiveStreamService {

    /**
     * Get all the drone data that can be broadcast live in this workspace.
     * @param workspaceId
     * @return
     */
    List<CapacityDeviceDTO> getLiveCapacity(String workspaceId);

    /**
     * Initiate a live streaming by publishing mqtt message.
     * @param liveParam Parameters needed for on-demand.
     * @return
     */
    HttpResultResponse liveStart(LiveTypeDTO liveParam);

    /**
     * Stop the live streaming by publishing mqtt message.
     * @param videoId
     * @return
     */
    HttpResultResponse liveStop(VideoId videoId);

    /**
     * Readjust the clarity of the live streaming by publishing mqtt messages.
     * @param liveParam
     * @return
     */
    HttpResultResponse liveSetQuality(LiveTypeDTO liveParam);

    /**
     * Switches the lens of the device during the live streaming.
     * @param liveParam
     * @return
     */
    HttpResultResponse liveLensChange(LiveTypeDTO liveParam);

}
