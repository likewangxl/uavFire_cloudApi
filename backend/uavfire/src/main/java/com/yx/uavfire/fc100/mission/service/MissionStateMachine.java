package com.yx.uavfire.fc100.mission.service;

import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus;

import java.util.Set;

/**
 * spec §4 状态机入口。所有状态推进必须经此接口（直接 SQL UPDATE 是禁止的）。
 */
public interface MissionStateMachine {

    /**
     * 推进任务状态。乐观锁 + 状态前提双重校验 + 自动写 mission_log。
     *
     * @throws com.yx.uavfire.fc100.common.Fc100BusinessException
     *   MISSION_NOT_FOUND 任务不存在
     *   STATUS_TRANSITION_FORBIDDEN 非法转移 or expectedFrom 不匹配
     *   VERSION_MISMATCH 乐观锁冲突
     */
    FireMissionEntity transit(TransitCommand cmd);

    /** 当前状态合法转出的事件集合，供前端按钮可见性 + 后端校验。 */
    Set<FireMissionEvent> allowedEvents(FireMissionStatus current);
}
