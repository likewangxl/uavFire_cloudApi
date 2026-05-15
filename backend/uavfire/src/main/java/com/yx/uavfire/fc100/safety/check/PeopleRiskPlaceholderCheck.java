package com.yx.uavfire.fc100.safety.check;

import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.safety.model.dto.SafetyCheckIssue;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckItem;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckPhase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** MVP PLACEHOLDER — 二期接人员风险识别 */
@Component
@Slf4j
public class PeopleRiskPlaceholderCheck implements SafetyCheck {
    public SafetyCheckItem item() { return SafetyCheckItem.PEOPLE_RISK_AREA; }

    public boolean appliesTo(SafetyCheckPhase phase) {
        return phase == SafetyCheckPhase.BEFORE_ROUTE || phase == SafetyCheckPhase.BEFORE_RELEASE;
    }

    public Optional<SafetyCheckIssue> evaluate(FireMissionEntity m) {
        log.warn("PLACEHOLDER: people-risk-area check skipped for mission={}", m.getMissionNo());
        return Optional.empty();
    }
}
