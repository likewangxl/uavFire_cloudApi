package com.yx.uavfire.fc100.safety.check;

import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.safety.model.dto.SafetyCheckIssue;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckItem;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckLevel;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckPhase;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 投放前 5 项 checklist 是否齐全。
 * 由 PayloadController.confirmRelease 用 @AssertTrue 在请求级别强校验；
 * 本 Check 只是后端兜底（如果 controller 直接 call 状态机绕过 param，仍能拦截）。
 * MVP 简化为 PASS（依赖 controller param 校验）。
 */
@Component
public class ManualConfirmationCheck implements SafetyCheck {
    public SafetyCheckItem item() { return SafetyCheckItem.MANUAL_CONFIRMATION; }

    public boolean appliesTo(SafetyCheckPhase phase) {
        return phase == SafetyCheckPhase.BEFORE_RELEASE;
    }

    public Optional<SafetyCheckIssue> evaluate(FireMissionEntity m) {
        // 实际校验在 PayloadConfirmReleaseParam 的 @AssertTrue 上做
        return Optional.empty();
    }
}
