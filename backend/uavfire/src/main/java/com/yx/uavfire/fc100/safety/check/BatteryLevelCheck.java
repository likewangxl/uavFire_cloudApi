package com.yx.uavfire.fc100.safety.check;

import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.safety.model.dto.SafetyCheckIssue;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckItem;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckLevel;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckPhase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** MVP PLACEHOLDER — 待接 OSD 后改为读 battery_percent <50 → ERROR */
@Component
@Slf4j
public class BatteryLevelCheck implements SafetyCheck {
    public SafetyCheckItem item() { return SafetyCheckItem.BATTERY_LEVEL; }

    public boolean appliesTo(SafetyCheckPhase phase) {
        return phase == SafetyCheckPhase.BEFORE_DELIVERY || phase == SafetyCheckPhase.BEFORE_RELEASE;
    }

    public Optional<SafetyCheckIssue> evaluate(FireMissionEntity m) {
        log.debug("PLACEHOLDER: battery check for {} not implemented", m.getMissionNo());
        return Optional.of(new SafetyCheckIssue(item().name(), SafetyCheckLevel.WARN,
            "battery level not verified (MVP placeholder)"));
    }
}
