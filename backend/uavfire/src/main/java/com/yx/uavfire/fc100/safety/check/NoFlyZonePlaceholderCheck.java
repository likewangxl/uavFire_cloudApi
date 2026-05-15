package com.yx.uavfire.fc100.safety.check;

import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.safety.model.dto.SafetyCheckIssue;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckItem;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckPhase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** MVP PLACEHOLDER — 二期接 DJI 禁飞区 API */
@Component
@Slf4j
public class NoFlyZonePlaceholderCheck implements SafetyCheck {
    public SafetyCheckItem item() { return SafetyCheckItem.NO_FLY_ZONE; }

    public boolean appliesTo(SafetyCheckPhase phase) {
        return phase == SafetyCheckPhase.BEFORE_ROUTE;
    }

    public Optional<SafetyCheckIssue> evaluate(FireMissionEntity m) {
        log.warn("PLACEHOLDER: no-fly zone check skipped for mission={}", m.getMissionNo());
        return Optional.empty();
    }
}
