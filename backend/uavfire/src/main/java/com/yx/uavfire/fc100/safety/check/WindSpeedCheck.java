package com.yx.uavfire.fc100.safety.check;

import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.safety.config.Fc100SafetyProperties;
import com.yx.uavfire.fc100.safety.model.dto.SafetyCheckIssue;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckItem;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckLevel;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckPhase;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 风速分档：
 * <ul>
 *   <li>&gt; 10 m/s → ERROR (拒批)</li>
 *   <li>6–10 m/s → WARN (操作员需确认)</li>
 *   <li>&lt; 6 m/s → PASS</li>
 * </ul>
 */
@Component
public class WindSpeedCheck implements SafetyCheck {
    private final Fc100SafetyProperties props;
    public WindSpeedCheck(Fc100SafetyProperties p) { this.props = p; }

    public SafetyCheckItem item() { return SafetyCheckItem.WIND_SPEED; }

    public boolean appliesTo(SafetyCheckPhase phase) {
        return phase == SafetyCheckPhase.BEFORE_APPROVE
            || phase == SafetyCheckPhase.BEFORE_ROUTE
            || phase == SafetyCheckPhase.BEFORE_RELEASE;
    }

    public Optional<SafetyCheckIssue> evaluate(FireMissionEntity m) {
        Double ws = m.getWindSpeedAtApproval();
        if (ws == null) {
            // 未审批阶段允许为空；审批阶段在 approve param 中已 @NotNull 保证非空
            return Optional.empty();
        }
        if (ws > props.getWindSpeedErrorThreshold()) {
            return Optional.of(new SafetyCheckIssue(item().name(), SafetyCheckLevel.ERROR,
                "wind speed " + ws + " m/s exceeds error threshold "
                    + props.getWindSpeedErrorThreshold()));
        }
        if (ws > props.getWindSpeedWarnThreshold()) {
            return Optional.of(new SafetyCheckIssue(item().name(), SafetyCheckLevel.WARN,
                "wind speed " + ws + " m/s requires operator confirmation"));
        }
        return Optional.empty();
    }
}
