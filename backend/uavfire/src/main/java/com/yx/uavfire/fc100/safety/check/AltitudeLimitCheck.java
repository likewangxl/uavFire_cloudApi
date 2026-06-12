package com.yx.uavfire.fc100.safety.check;

import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.safety.model.dto.SafetyCheckIssue;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckItem;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckLevel;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckPhase;
import com.yx.uavfire.fc100.waypoint.config.Fc100WaypointProperties;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** spec §5.2: 巡航高度相对起飞点 < 1500m (FC100 官方最大相对高度) */
@Component
public class AltitudeLimitCheck implements SafetyCheck {
    private static final double MAX_RELATIVE_ALT = 1500.0;
    /** 巡航相对起飞点高 = 投放高(AGL) + 此余量，必须与 WaypointPlannerServiceImpl 的 cruiseAlt 公式一致。 */
    private static final double CRUISE_MARGIN_M = 20.0;
    private final Fc100WaypointProperties props;
    public AltitudeLimitCheck(Fc100WaypointProperties props) { this.props = props; }

    public SafetyCheckItem item() { return SafetyCheckItem.ALTITUDE_LIMIT; }

    public boolean appliesTo(SafetyCheckPhase phase) {
        return phase == SafetyCheckPhase.BEFORE_ROUTE;
    }

    public Optional<SafetyCheckIssue> evaluate(FireMissionEntity m) {
        // 航点高度已统一为“相对起飞点 AGL”（WPML relativeToStartPoint），巡航高即相对高，
        // 不再依赖火点/起飞点绝对椭球高。与 WaypointPlanner 同源：cruise = dropAltAgl + 20m。
        double relative = props.getDropAltitudeAglM() + CRUISE_MARGIN_M;
        if (relative > MAX_RELATIVE_ALT) {
            return Optional.of(new SafetyCheckIssue(item().name(), SafetyCheckLevel.ERROR,
                "cruise altitude relative to takeoff " + (int) relative + "m exceeds "
                    + (int) MAX_RELATIVE_ALT + "m"));
        }
        return Optional.empty();
    }
}
