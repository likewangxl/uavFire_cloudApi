package com.yx.uavfire.fc100.safety.service;

import com.yx.uavfire.fc100.safety.model.dto.SafetyCheckResult;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckPhase;

public interface SafetyCheckService {
    /**
     * 在 phase 阶段对 mission 进行全套安全校验。
     * @return passed=true 表示无 ERROR；issues 列出全部 WARN/ERROR。
     */
    SafetyCheckResult check(SafetyCheckPhase phase, String missionNo);
}
