package com.yx.uavfire.fc100.mission.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.mission.dao.FireMissionLogMapper;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.dto.MissionLogDTO;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionLogEntity;
import com.yx.uavfire.fc100.mission.service.MissionLogService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class MissionLogServiceImpl implements MissionLogService {

    private final FireMissionMapper missionMapper;
    private final FireMissionLogMapper logMapper;

    public MissionLogServiceImpl(FireMissionMapper missionMapper, FireMissionLogMapper logMapper) {
        this.missionMapper = missionMapper;
        this.logMapper = logMapper;
    }

    @Override
    public List<MissionLogDTO> listByMissionNo(String missionNo, int limit) {
        FireMissionEntity mission = missionMapper.selectOne(
            new QueryWrapper<FireMissionEntity>()
                .eq("mission_no", missionNo)
                .eq("deleted", 0));
        if (mission == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.MISSION_NOT_FOUND, missionNo);
        }

        List<FireMissionLogEntity> logs = logMapper.selectList(
            new QueryWrapper<FireMissionLogEntity>()
                .eq("mission_id", mission.getId())
                .orderByDesc("create_time")
                .last("LIMIT " + limit));

        return logs.stream().map(e -> {
            MissionLogDTO dto = new MissionLogDTO();
            BeanUtils.copyProperties(e, dto);
            return dto;
        }).collect(Collectors.toList());
    }
}
