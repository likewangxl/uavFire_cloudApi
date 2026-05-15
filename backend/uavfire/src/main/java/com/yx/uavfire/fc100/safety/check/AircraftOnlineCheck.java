package com.yx.uavfire.fc100.safety.check;

import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.safety.model.dto.SafetyCheckIssue;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckItem;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckLevel;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckPhase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * MVP PLACEHOLDER：当前独立工程未接 Cloud API OSD（FC100 不在 Cloud API 支持机型）。
 * 真实在线判定二期通过 Delivery Sync getDeviceProperties 实现。
 * 当前永远返 WARN（不阻塞但留痕），如果 aircraftSn 未绑定才 ERROR。
 */
@Component
@Slf4j
public class AircraftOnlineCheck implements SafetyCheck {

    public SafetyCheckItem item() { return SafetyCheckItem.AIRCRAFT_ONLINE; }

    public boolean appliesTo(SafetyCheckPhase phase) {
        return phase == SafetyCheckPhase.BEFORE_APPROVE
            || phase == SafetyCheckPhase.BEFORE_DELIVERY
            || phase == SafetyCheckPhase.BEFORE_RELEASE;
    }

    public Optional<SafetyCheckIssue> evaluate(FireMissionEntity m) {
        if (m.getAircraftSn() == null && phaseRequiresSn(m)) {
            return Optional.of(new SafetyCheckIssue(item().name(), SafetyCheckLevel.ERROR,
                "aircraftSn not bound to mission"));
        }
        log.debug("PLACEHOLDER: aircraft_online check for mission={} sn={} not implemented in MVP",
            m.getMissionNo(), m.getAircraftSn());
        return Optional.of(new SafetyCheckIssue(item().name(), SafetyCheckLevel.WARN,
            "aircraft online status not verified (MVP placeholder — pending Delivery Sync OSD)"));
    }

    private boolean phaseRequiresSn(FireMissionEntity m) {
        // BEFORE_APPROVE 阶段 aircraftSn 还允许为空，由 approve param 提供
        return false;
    }
}
