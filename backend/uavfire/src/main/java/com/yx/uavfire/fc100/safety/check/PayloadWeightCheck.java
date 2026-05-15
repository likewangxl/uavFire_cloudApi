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
 * 载重 = water_load_liters * 1kg/L + dryWeight。spec §5.2 默认上限 82kg。
 * FC100 飞行载重官方为 80/65kg；82 为机械张力极限保守值。
 */
@Component
public class PayloadWeightCheck implements SafetyCheck {
    private final Fc100SafetyProperties props;
    public PayloadWeightCheck(Fc100SafetyProperties p) { this.props = p; }

    public SafetyCheckItem item() { return SafetyCheckItem.PAYLOAD_WEIGHT; }

    public boolean appliesTo(SafetyCheckPhase phase) {
        return phase == SafetyCheckPhase.BEFORE_APPROVE
            || phase == SafetyCheckPhase.BEFORE_RELEASE;
    }

    public Optional<SafetyCheckIssue> evaluate(FireMissionEntity m) {
        Double water = m.getWaterLoadLiters();
        if (water == null) return Optional.empty();
        double total = water * 1.0 + props.getDryWeightKg();
        if (total > props.getPayloadWeightMaxKg()) {
            return Optional.of(new SafetyCheckIssue(item().name(), SafetyCheckLevel.ERROR,
                "payload weight " + total + " kg exceeds max " + props.getPayloadWeightMaxKg() + " kg"));
        }
        return Optional.empty();
    }
}
