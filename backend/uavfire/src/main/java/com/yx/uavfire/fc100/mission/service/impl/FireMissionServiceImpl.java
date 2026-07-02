package com.yx.uavfire.fc100.mission.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.dto.FireMissionDTO;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus;
import com.yx.uavfire.fc100.mission.service.FireMissionService;
import com.yx.uavfire.fc100.mission.service.MissionStateMachine;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class FireMissionServiceImpl implements FireMissionService {

    private final FireMissionMapper mapper;
    private final MissionStateMachine sm;
    private final Clock clock;

    public FireMissionServiceImpl(FireMissionMapper m, MissionStateMachine sm, Clock clock) {
        this.mapper = m;
        this.sm = sm;
        this.clock = clock;
    }

    @Override
    public FireMissionDTO detail(String no) {
        FireMissionEntity e = mapper.selectOne(
            new QueryWrapper<FireMissionEntity>()
                .eq("mission_no", no)
                .eq("deleted", 0));
        if (e == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.MISSION_NOT_FOUND, no);
        }
        return toDto(e);
    }

    @Override
    public List<FireMissionDTO> list(String workspaceId, String status, int page, int size) {
        QueryWrapper<FireMissionEntity> w = new QueryWrapper<>();
        w.eq("deleted", 0);
        if (workspaceId != null) w.eq("workspace_id", workspaceId);
        if (status != null) w.eq("status", status);
        w.orderByDesc("create_time");
        Page<FireMissionEntity> p = mapper.selectPage(new Page<>(page, size), w);
        return p.getRecords().stream().map(this::toDto).collect(Collectors.toList());
    }

    private FireMissionDTO toDto(FireMissionEntity e) {
        FireMissionDTO d = new FireMissionDTO();
        BeanUtils.copyProperties(e, d);
        if (e.getReleaseTokenExpiresAt() != null
            && FireMissionStatus.PAYLOAD_RELEASE_PENDING.name().equals(e.getStatus())
            && e.getReleaseTokenUsedAt() == null) {
            d.setReleasePendingRemainingMs(Math.max(0L, e.getReleaseTokenExpiresAt() - clock.now()));
        }
        d.setAvailableActions(sm.allowedEvents(FireMissionStatus.valueOf(e.getStatus()))
            .stream().map(FireMissionEvent::name).collect(Collectors.toList()));
        return d;
    }
}
