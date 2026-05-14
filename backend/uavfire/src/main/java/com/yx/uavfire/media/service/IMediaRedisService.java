package com.yx.uavfire.media.service;

import com.yx.uavfire.media.model.MediaFileCountDTO;

/**
 * @author sean
 * @version 0.2
 * @date 2021/12/9
 */
public interface IMediaRedisService {

    void setMediaCount(String gatewaySn, String jobId, MediaFileCountDTO mediaFile);

    MediaFileCountDTO getMediaCount(String gatewaySn, String jobId);

    boolean delMediaCount(String gatewaySn, String jobId);

    boolean detMediaCountByDeviceSn(String gatewaySn);

    void setMediaHighestPriority(String gatewaySn, MediaFileCountDTO mediaFile);

    MediaFileCountDTO getMediaHighestPriority(String gatewaySn);

    boolean delMediaHighestPriority(String gatewaySn);

}
