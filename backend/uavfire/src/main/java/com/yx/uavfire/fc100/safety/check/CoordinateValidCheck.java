package com.yx.uavfire.fc100.safety.check;

import com.yx.uavfire.fc100.event.dao.FireEventMapper;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.safety.model.dto.SafetyCheckIssue;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckItem;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckLevel;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckPhase;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class CoordinateValidCheck implements SafetyCheck {
    private final FireEventMapper eventMapper;
    public CoordinateValidCheck(FireEventMapper m) { this.eventMapper = m; }

    public SafetyCheckItem item() { return SafetyCheckItem.FIRE_COORDINATE_VALID; }

    public boolean appliesTo(SafetyCheckPhase phase) {
        return phase == SafetyCheckPhase.BEFORE_APPROVE || phase == SafetyCheckPhase.BEFORE_ROUTE;
    }

    public Optional<SafetyCheckIssue> evaluate(FireMissionEntity m) {
        FireEventEntity e = eventMapper.selectById(m.getFireEventId());
        if (e == null || e.getLat() == null || e.getLng() == null) {
            return Optional.of(new SafetyCheckIssue(item().name(), SafetyCheckLevel.ERROR,
                "fire coordinate missing"));
        }
        if (e.getLat() < -90 || e.getLat() > 90 || e.getLng() < -180 || e.getLng() > 180) {
            return Optional.of(new SafetyCheckIssue(item().name(), SafetyCheckLevel.ERROR,
                "fire coordinate out of range: " + e.getLat() + "," + e.getLng()));
        }
        return Optional.empty();
    }
}
