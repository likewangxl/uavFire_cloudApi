package com.yx.uavfire.fc100.safety.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.safety.check.SafetyCheck;
import com.yx.uavfire.fc100.safety.model.dto.SafetyCheckIssue;
import com.yx.uavfire.fc100.safety.model.dto.SafetyCheckResult;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckLevel;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckPhase;
import com.yx.uavfire.fc100.safety.service.SafetyCheckService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class SafetyCheckServiceImpl implements SafetyCheckService {

    private final List<SafetyCheck> checks;
    private final FireMissionMapper missionMapper;

    public SafetyCheckServiceImpl(List<SafetyCheck> checks, FireMissionMapper m) {
        this.checks = checks;
        this.missionMapper = m;
    }

    @Override
    public SafetyCheckResult check(SafetyCheckPhase phase, String missionNo) {
        FireMissionEntity m = missionMapper.selectOne(
            new QueryWrapper<FireMissionEntity>().eq("mission_no", missionNo).eq("deleted", 0));
        if (m == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.MISSION_NOT_FOUND, missionNo);
        }

        List<SafetyCheckIssue> issues = new ArrayList<>();
        for (SafetyCheck c : checks) {
            if (!c.appliesTo(phase)) continue;
            c.evaluate(m).ifPresent(issues::add);
        }
        SafetyCheckResult r = new SafetyCheckResult();
        boolean anyError = issues.stream().anyMatch(i -> i.getLevel() == SafetyCheckLevel.ERROR);
        r.setPassed(!anyError);
        r.setIssues(issues);
        return r;
    }
}
