package com.yx.uavfire.manage.service.impl;

import com.yx.uavfire.firedetection.AiServiceClient;
import com.yx.uavfire.manage.model.dto.*;
import com.yx.uavfire.manage.model.param.DeviceQueryParam;
import com.yx.uavfire.manage.service.*;
import com.dji.sdk.cloudapi.device.DeviceDomainEnum;
import com.dji.sdk.cloudapi.device.VideoId;
import com.dji.sdk.cloudapi.livestream.*;
import com.dji.sdk.cloudapi.livestream.api.AbstractLivestreamService;
import com.dji.sdk.common.HttpResultResponse;
import com.dji.sdk.common.SDKManager;
import com.dji.sdk.mqtt.services.ServicesReplyData;
import com.dji.sdk.mqtt.services.TopicServicesResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @author sean.zhou
 * @date 2021/11/22
 * @version 0.1
 *
 * NOTE (2026-05-21, MSDK Migration Phase 1): Cloud SDK livestream impl.
 * Deprecated; superseded by DualStream agent pipeline. Kept as fallback
 * during phase 1. See docs/MSDK_MIGRATION_PLAN.md. Phase 2 removes this.
 */
@Service
@Transactional
@Deprecated
public class LiveStreamServiceImpl implements ILiveStreamService {

    @Autowired
    private ICapacityCameraService capacityCameraService;

    @Autowired
    private IDeviceService deviceService;

    @Autowired
    private IWorkspaceService workspaceService;

    @Autowired
    private IDeviceRedisService deviceRedisService;

    @Autowired
    private AbstractLivestreamService abstractLivestreamService;

    @Value("${livestream.playback.webrtc-host:}")
    private String webrtcPlaybackHost;

    @Value("${livestream.playback.webrtc-port:#{null}}")
    private Integer webrtcPlaybackPort;

    @Autowired
    private AiServiceClient aiServiceClient;

    @Value("${ai-service.zlm-rtsp-host:127.0.0.1}")
    private String zlmRtspHost;

    @Value("${ai-service.zlm-rtsp-port:8554}")
    private int zlmRtspPort;

    @Value("${ai-service.auto-trigger-on-livestream:true}")
    private boolean aiAutoTriggerOnLivestream;

    @Override
    public List<CapacityDeviceDTO> getLiveCapacity(String workspaceId) {

        // Query all devices in this workspace.
        List<DeviceDTO> devicesList = deviceService.getDevicesByParams(
                DeviceQueryParam.builder()
                        .workspaceId(workspaceId)
                        .domains(List.of(DeviceDomainEnum.DRONE.getDomain(), DeviceDomainEnum.DOCK.getDomain()))
                        .build());

        // Query the live capability of each drone.
        return devicesList.stream()
                .filter(device -> deviceRedisService.checkDeviceOnline(device.getDeviceSn()))
                .map(device -> CapacityDeviceDTO.builder()
                        .name(Objects.requireNonNullElse(device.getNickname(), device.getDeviceName()))
                        .sn(device.getDeviceSn())
                        .camerasList(capacityCameraService.getCapacityCameraByDeviceSn(device.getDeviceSn()))
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public HttpResultResponse liveStart(LiveTypeDTO liveParam) {
        // Check if this lens is available live.
        HttpResultResponse<DeviceDTO> responseResult = this.checkBeforeLive(liveParam.getVideoId());
        if (HttpResultResponse.CODE_SUCCESS != responseResult.getCode()) {
            return responseResult;
        }

        ILivestreamUrl url = LiveStreamProperty.get(liveParam.getUrlType());
        url = setExt(liveParam.getUrlType(), url, liveParam.getVideoId());

        TopicServicesResponse<ServicesReplyData<LiveStartPushResponse>> response = abstractLivestreamService.liveStartPush(
                SDKManager.getDeviceSDK(responseResult.getData().getDeviceSn()),
                new LiveStartPushRequest()
                        .setUrl(url)
                        .setUrlType(liveParam.getUrlType())
                        .setVideoId(liveParam.getVideoId())
                        .setVideoQuality(liveParam.getVideoQuality()));

        if (!response.getData().getResult().isSuccess()) {
            return HttpResultResponse.error(response.getData().getResult());
        }

        LiveDTO live = new LiveDTO();

        switch (liveParam.getUrlType()) {
            case RTMP:
                live.setUrl(buildRtmpPlaybackUrl((LivestreamRtmpUrl) url));
                break;
            case GB28181:
                LivestreamGb28181Url gb28181 = (LivestreamGb28181Url) url;
                live.setUrl(new StringBuilder()
                        .append("webrtc://")
                        .append(gb28181.getServerIP())
                        .append("/live/")
                        .append(gb28181.getAgentID())
                        .append("@")
                        .append(gb28181.getChannel())
                        .toString());
                break;
            case RTSP:
                LiveStartPushResponse rtspOut = response.getData().getOutput();
                if (rtspOut != null && rtspOut.getUrl() != null) {
                    live.setUrl(rtspOut.getUrl());
                }
                break;
            case WHIP:
                live.setUrl(url.toString().replace("whip", "whep"));
                break;
            default:
                return HttpResultResponse.error(LiveErrorCodeEnum.URL_TYPE_NOT_SUPPORTED);
        }

        // 自动触发火情识别 (trigger #2): 直播推流成功后，让 ai-service 拉 ZLM RTSP 跑 YOLO。
        // ZLM 上的 stream 名是 <droneSn>-<payloadIndex>，不带 videoType 后缀；
        // VideoId.toString() 会多拼一段 "/<videoType>-0"，必须丢掉，否则拼出的 RTSP URL 会 404。
        // 注意：responseResult.getData() 在 RC 接管模式下 deviceSn 是 RC 的 SN，
        // 真正的飞机 SN 在 childDeviceSn 字段 / VideoId.droneSn，必须用飞机 SN 才能命中 OSD 缓存。
        if (aiAutoTriggerOnLivestream) {
            VideoId vid = liveParam.getVideoId();
            String droneSn;
            String streamVideoId;
            if (vid != null && vid.getPayloadIndex() != null) {
                droneSn = vid.getDroneSn();
                streamVideoId = vid.getDroneSn() + "/" + vid.getPayloadIndex().toString();
            } else {
                droneSn = StringUtils.hasText(responseResult.getData().getChildDeviceSn())
                        ? responseResult.getData().getChildDeviceSn()
                        : responseResult.getData().getDeviceSn();
                streamVideoId = AiServiceClient.defaultVideoIdForDrone(droneSn);
            }
            String rtspUrl = AiServiceClient.rtspUrlForVideoId(streamVideoId, zlmRtspHost, zlmRtspPort);
            aiServiceClient.startDetection(aiServiceClient.fireTaskIdForDrone(droneSn), droneSn, rtspUrl, "");
        }

        return HttpResultResponse.success(live);
    }

    @Override
    public HttpResultResponse liveStop(VideoId videoId) {
        HttpResultResponse<DeviceDTO> responseResult = this.checkBeforeLive(videoId);
        if (HttpResultResponse.CODE_SUCCESS != responseResult.getCode()) {
            return responseResult;
        }

        TopicServicesResponse<ServicesReplyData> response = abstractLivestreamService.liveStopPush(
                SDKManager.getDeviceSDK(responseResult.getData().getDeviceSn()), new LiveStopPushRequest()
                        .setVideoId(videoId));
        if (!response.getData().getResult().isSuccess()) {
            return HttpResultResponse.error(response.getData().getResult());
        }

        if (aiAutoTriggerOnLivestream) {
            aiServiceClient.stopDetection(
                    aiServiceClient.fireTaskIdForDrone(responseResult.getData().getDeviceSn()));
        }

        return HttpResultResponse.success();
    }

    @Override
    public HttpResultResponse liveSetQuality(LiveTypeDTO liveParam) {
        HttpResultResponse<DeviceDTO> responseResult = this.checkBeforeLive(liveParam.getVideoId());
        if (responseResult.getCode() != 0) {
            return responseResult;
        }

        TopicServicesResponse<ServicesReplyData> response = abstractLivestreamService.liveSetQuality(
                SDKManager.getDeviceSDK(responseResult.getData().getDeviceSn()), new LiveSetQualityRequest()
                        .setVideoQuality(liveParam.getVideoQuality())
                        .setVideoId(liveParam.getVideoId()));
        if (!response.getData().getResult().isSuccess()) {
            return HttpResultResponse.error(response.getData().getResult());
        }

        return HttpResultResponse.success();
    }

    @Override
    public HttpResultResponse liveLensChange(LiveTypeDTO liveParam) {
        HttpResultResponse<DeviceDTO> responseResult = this.checkBeforeLive(liveParam.getVideoId());
        if (HttpResultResponse.CODE_SUCCESS != responseResult.getCode()) {
            return responseResult;
        }

        TopicServicesResponse<ServicesReplyData> response = abstractLivestreamService.liveLensChange(
                SDKManager.getDeviceSDK(responseResult.getData().getDeviceSn()), new LiveLensChangeRequest()
                        .setVideoType(liveParam.getVideoType())
                        .setVideoId(liveParam.getVideoId()));

        if (!response.getData().getResult().isSuccess()) {
            return HttpResultResponse.error(response.getData().getResult());
        }

        return HttpResultResponse.success();
    }

    /**
     * Check if this lens is available live.
     * @param videoId
     * @return
     */
    private HttpResultResponse<DeviceDTO> checkBeforeLive(VideoId videoId) {
        if (Objects.isNull(videoId)) {
            return HttpResultResponse.error(LiveErrorCodeEnum.ERROR_PARAMETERS);
        }

        Optional<DeviceDTO> deviceOpt = deviceService.getDeviceBySn(videoId.getDroneSn());
        // Check if the gateway device connected to this drone exists
        if (deviceOpt.isEmpty()) {
            return HttpResultResponse.error(LiveErrorCodeEnum.NO_AIRCRAFT);
        }

        if (DeviceDomainEnum.DOCK == deviceOpt.get().getDomain()) {
            return HttpResultResponse.success(deviceOpt.get());
        }
        List<DeviceDTO> gatewayList = deviceService.getDevicesByParams(
                DeviceQueryParam.builder()
                        .childSn(videoId.getDroneSn())
                        .build());
        if (gatewayList.isEmpty()) {
            return HttpResultResponse.error(LiveErrorCodeEnum.NO_FLIGHT_CONTROL);
        }

        return HttpResultResponse.success(gatewayList.get(0));
    }

    /**
     * This is business-customized logic and is only used for testing.
     * @param type
     * @param url
     * @param videoId
     */
    private ILivestreamUrl setExt(UrlTypeEnum type, ILivestreamUrl url, VideoId videoId) {
        switch (type) {
            case RTMP:
                LivestreamRtmpUrl rtmpUrl = (LivestreamRtmpUrl) url.clone();
                return rtmpUrl.setUrl(rtmpUrl.getUrl() + videoId.getDroneSn() + "-" + videoId.getPayloadIndex().toString());
            case GB28181:
                String random = String.valueOf(Math.abs(videoId.getDroneSn().hashCode()) % 1000);
                LivestreamGb28181Url gbUrl = (LivestreamGb28181Url) url.clone();
                gbUrl.setAgentID(gbUrl.getAgentID().substring(0, 20 - random.length()) + random);
                String deviceType = String.valueOf(videoId.getPayloadIndex().getType().getType());
                return gbUrl.setChannel(gbUrl.getChannel().substring(0, 20 - deviceType.length()) + deviceType);
            case WHIP:
                LivestreamWhipUrl whipUrl = (LivestreamWhipUrl) url.clone();
                return whipUrl.setUrl(whipUrl.getUrl() + videoId.getDroneSn() + "-" + videoId.getPayloadIndex().toString());
        }
        return url;
    }

    private String buildRtmpPlaybackUrl(LivestreamRtmpUrl rtmpUrl) {
        URI source = URI.create(rtmpUrl.getUrl());
        String host = StringUtils.hasText(webrtcPlaybackHost) ? webrtcPlaybackHost : source.getHost();

        StringBuilder playbackUrl = new StringBuilder()
                .append("webrtc://")
                .append(host)
                .append(source.getPath());

        Optional.ofNullable(webrtcPlaybackPort)
                .filter(value -> value > 0)
                .ifPresent(port -> playbackUrl.insert("webrtc://".length() + host.length(), ":" + port));

        if (StringUtils.hasText(source.getQuery())) {
            playbackUrl.append("?").append(source.getQuery());
        }
        return playbackUrl.toString();
    }
}
