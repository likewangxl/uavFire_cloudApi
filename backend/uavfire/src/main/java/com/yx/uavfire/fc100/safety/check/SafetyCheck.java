package com.yx.uavfire.fc100.safety.check;

import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.safety.model.dto.SafetyCheckIssue;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckItem;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckPhase;

import java.util.Optional;

/**
 * 单一职责的校验接口。每项 Check 一个 @Component class，
 * SafetyCheckService 启动时注入 List<SafetyCheck> 自动发现。
 */
public interface SafetyCheck {
    SafetyCheckItem item();
    boolean appliesTo(SafetyCheckPhase phase);
    /** empty=PASS；present=issue */
    Optional<SafetyCheckIssue> evaluate(FireMissionEntity mission);
}
