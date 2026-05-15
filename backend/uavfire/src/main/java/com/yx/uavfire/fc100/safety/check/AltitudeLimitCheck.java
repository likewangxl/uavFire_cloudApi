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

/** spec §5.2: 巡航高度相对起飞点 < 1500m (FC100 官方最大相对高度) */
@Component
public class AltitudeLimitCheck implements SafetyCheck {
    private static final double MAX_RELATIVE_ALT = 1500.0;
    private final FireEventMapper eventMapper;
    public AltitudeLimitCheck(FireEventMapper m) { this.eventMapper = m; }

    public SafetyCheckItem item() { return SafetyCheckItem.ALTITUDE_LIMIT; }

    public boolean appliesTo(SafetyCheckPhase phase) {
        return phase == SafetyCheckPhase.BEFORE_ROUTE;
    }

    public Optional<SafetyCheckIssue> evaluate(FireMissionEntity m) {
        if (m.getTakeoffAlt() == null) return Optional.empty();
        FireEventEntity e = eventMapper.selectById(m.getFireEventId());
        if (e == null || e.getAlt() == null) return Optional.empty();
        // cruiseAlt 估算 = max(fireAlt+40, takeoffAlt+50)
        double cruiseAlt = Math.max(e.getAlt() + 40, m.getTakeoffAlt() + 50);
        double relative = cruiseAlt - m.getTakeoffAlt();
        if (relative > MAX_RELATIVE_ALT) {
            return Optional.of(new SafetyCheckIssue(item().name(), SafetyCheckLevel.ERROR,
                "cruise altitude relative to takeoff " + (int) relative + "m exceeds "
                    + (int) MAX_RELATIVE_ALT + "m"));
        }
        return Optional.empty();
    }
}
