package com.yx.uavfire.fc100.safety.check;

import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.safety.config.Fc100SafetyProperties;
import com.yx.uavfire.fc100.safety.model.dto.SafetyCheckIssue;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckItem;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckLevel;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckPhase;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/** Approver must be a different person from the dispatcher when four-eyes is enabled. */
@Component
public class FourEyesCheck implements SafetyCheck {

    private final Fc100SafetyProperties properties;

    public FourEyesCheck(Fc100SafetyProperties properties) {
        this.properties = properties;
    }

    @Override
    public SafetyCheckItem item() {
        return SafetyCheckItem.MANUAL_CONFIRMATION;
    }

    @Override
    public boolean appliesTo(SafetyCheckPhase phase) {
        return phase == SafetyCheckPhase.BEFORE_APPROVE && properties.isFourEyesEnabled();
    }

    @Override
    public Optional<SafetyCheckIssue> evaluate(FireMissionEntity mission) {
        if (!properties.isFourEyesEnabled()) {
            return Optional.empty();
        }
        String approver = mission == null ? null : mission.getApproverId();
        String creator = mission == null ? null : mission.getCreatedBy();
        if (!StringUtils.hasText(approver) || !StringUtils.hasText(creator)) {
            return Optional.of(issue("four-eyes requires both creator and approver"));
        }
        if (approver.trim().equals(creator.trim())) {
            return Optional.of(issue("approver must differ from the mission creator"));
        }
        return Optional.empty();
    }

    private static SafetyCheckIssue issue(String message) {
        return new SafetyCheckIssue(SafetyCheckItem.MANUAL_CONFIRMATION.name(), SafetyCheckLevel.ERROR, message);
    }
}
