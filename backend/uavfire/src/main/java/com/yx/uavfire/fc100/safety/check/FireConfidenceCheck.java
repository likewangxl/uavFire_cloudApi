package com.yx.uavfire.fc100.safety.check;

import com.yx.uavfire.fc100.event.dao.FireEventMapper;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.safety.model.dto.SafetyCheckIssue;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckItem;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckLevel;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckPhase;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

@Component
public class FireConfidenceCheck implements SafetyCheck {
    private static final BigDecimal MIN = new BigDecimal("0.75");
    private final FireEventMapper eventMapper;
    public FireConfidenceCheck(FireEventMapper m) { this.eventMapper = m; }

    public SafetyCheckItem item() { return SafetyCheckItem.FIRE_CONFIDENCE; }

    public boolean appliesTo(SafetyCheckPhase phase) {
        return phase == SafetyCheckPhase.BEFORE_APPROVE;
    }

    public Optional<SafetyCheckIssue> evaluate(FireMissionEntity m) {
        FireEventEntity e = eventMapper.selectById(m.getFireEventId());
        if (e == null || e.getConfidence() == null) {
            return Optional.of(new SafetyCheckIssue(item().name(), SafetyCheckLevel.ERROR,
                "fire event or confidence missing"));
        }
        if (e.getConfidence().compareTo(MIN) < 0) {
            return Optional.of(new SafetyCheckIssue(item().name(), SafetyCheckLevel.ERROR,
                "confidence too low: " + e.getConfidence()));
        }
        return Optional.empty();
    }
}
