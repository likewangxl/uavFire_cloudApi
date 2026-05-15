package com.yx.uavfire.fc100.waypoint.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yx.uavfire.fc100.waypoint.model.entity.MissionWaypointEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MissionWaypointMapper extends BaseMapper<MissionWaypointEntity> {
}
