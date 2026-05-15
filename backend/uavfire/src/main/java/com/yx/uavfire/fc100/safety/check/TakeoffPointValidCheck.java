package com.yx.uavfire.fc100.safety.check;

import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.safety.model.dto.SafetyCheckIssue;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckItem;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckLevel;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckPhase;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class TakeoffPointValidCheck implements SafetyCheck {
    public SafetyCheckItem item() { return SafetyCheckItem.TAKEOFF_POINT_VALID; }

    public boolean appliesTo(SafetyCheckPhase phase) {
        return phase == SafetyCheckPhase.BEFORE_ROUTE;
    }

    public Optional<SafetyCheckIssue> evaluate(FireMissionEntity m) {
        if (m.getTakeoffLat() == null || m.getTakeoffLng() == null) {
            return Optional.of(new SafetyCheckIssue(item().name(), SafetyCheckLevel.ERROR,
                "takeoff point not set (must approve with takeoffLat/Lng)"));
        }
        if (m.getTakeoffLat() < -90 || m.getTakeoffLat() > 90
            || m.getTakeoffLng() < -180 || m.getTakeoffLng() > 180) {
            return Optional.of(new SafetyCheckIssue(item().name(), SafetyCheckLevel.ERROR,
                "takeoff coordinate out of range"));
        }
        return Optional.empty();
    }
}
