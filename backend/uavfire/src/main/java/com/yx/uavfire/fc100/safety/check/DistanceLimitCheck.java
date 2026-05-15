package com.yx.uavfire.fc100.safety.check;

import com.yx.uavfire.fc100.common.GeoUtils;
import com.yx.uavfire.fc100.event.dao.FireEventMapper;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.safety.model.dto.SafetyCheckIssue;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckItem;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckLevel;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckPhase;
import com.yx.uavfire.fc100.waypoint.config.Fc100WaypointProperties;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class DistanceLimitCheck implements SafetyCheck {
    private final FireEventMapper eventMapper;
    private final Fc100WaypointProperties props;

    public DistanceLimitCheck(FireEventMapper e, Fc100WaypointProperties p) {
        this.eventMapper = e;
        this.props = p;
    }

    public SafetyCheckItem item() { return SafetyCheckItem.DISTANCE_LIMIT; }

    public boolean appliesTo(SafetyCheckPhase phase) {
        return phase == SafetyCheckPhase.BEFORE_ROUTE;
    }

    public Optional<SafetyCheckIssue> evaluate(FireMissionEntity m) {
        if (m.getTakeoffLat() == null || m.getTakeoffLng() == null) {
            return Optional.empty();
        }
        FireEventEntity e = eventMapper.selectById(m.getFireEventId());
        if (e == null) return Optional.empty();

        double d = GeoUtils.distance(m.getTakeoffLat(), m.getTakeoffLng(),
            e.getLat(), e.getLng());
        if (d > props.getMaxDistanceFromTakeoffM()) {
            return Optional.of(new SafetyCheckIssue(item().name(), SafetyCheckLevel.ERROR,
                "takeoff-to-fire " + (int) d + "m exceeds max "
                    + (int) props.getMaxDistanceFromTakeoffM() + "m"));
        }
        return Optional.empty();
    }
}
