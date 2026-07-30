package com.yx.uavfire.fc100.event.service;

import com.yx.uavfire.fc100.event.model.dto.FireEventCreateResponse;
import com.yx.uavfire.fc100.event.model.dto.FireEventDecisionResult;
import com.yx.uavfire.fc100.event.model.dto.FireEventDTO;
import com.yx.uavfire.fc100.event.model.dto.FireEventHistoryDTO;
import com.yx.uavfire.fc100.event.model.dto.FireEventRecheckResultDTO;
import com.yx.uavfire.fc100.event.model.param.FireEventActionParam;
import com.yx.uavfire.fc100.event.model.param.FireEventCreateParam;
import com.yx.uavfire.fc100.event.model.param.FireLaserLocationParam;
import com.yx.uavfire.fc100.event.model.param.FireEventRecheckResultParam;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

public interface FireEventService {

    /**
     * 火情上报入口。三档置信度逻辑见 spec §3.1：
     * <ul>
     *   <li>&lt; 0.75：仅保存事件，status=LOW_CONFIDENCE，不建任务</li>
     *   <li>0.75–0.90：保存事件 + 自动建 WAITING_REVIEW 任务</li>
     *   <li>≥ 0.90：同上，并标 isHighConfidence=1</li>
     * </ul>
     * 同 eventId 重复上报：返回已存在事件的任务（去重）。
     */
    FireEventCreateResponse create(FireEventCreateParam param);

    boolean applyLaserLocation(String eventId, FireLaserLocationParam param);

    boolean markLaserLocationFailed(String eventId, String reason, long sourceTs);

    FireEventDecisionResult confirm(String eventId, FireEventActionParam param, HttpServletRequest request);

    FireEventDecisionResult reject(String eventId, FireEventActionParam param, HttpServletRequest request);

    FireEventRecheckResultDTO recordRecheckResult(String eventId, FireEventRecheckResultParam param, HttpServletRequest request);

    default boolean attachVisibleImage(String eventId, String sourceEventId, String visibleImageUrl, String timestamp) {
        return attachVisibleImage(eventId, sourceEventId, visibleImageUrl, timestamp, null, null);
    }

    boolean attachVisibleImage(
        String eventId,
        String sourceEventId,
        String visibleImageUrl,
        String timestamp,
        String thermalSourceEventId,
        String thermalImageUrl);

    boolean recordVisibleConfirmationStatus(
        String eventId,
        String sourceEventId,
        String action,
        String visibleImageUrl,
        String timestamp,
        String thermalSourceEventId,
        String thermalImageUrl);

    FireEventDTO get(String eventId);

    /**
     * 查询火情事件列表，按 createTime DESC 排序。
     *
     * @param workspaceId 工作空间过滤（null=不过滤）
     * @param status      状态过滤（null=不过滤）
     * @param limit       最多返回条数，默认 50
     */
    List<FireEventDTO> list(String workspaceId, String status, int limit);

    List<FireEventHistoryDTO> listHistory(String eventId, int limit);
}
