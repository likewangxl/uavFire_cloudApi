package com.yx.uavfire.wayline.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yx.uavfire.wayline.model.entity.PlannedWaylineEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface IPlannedWaylineMapper extends BaseMapper<PlannedWaylineEntity> {
    @Select("SELECT id, planned_wayline_id, workspace_id, aircraft_sn, flight_id, drone_sn, task_status, " +
        "last_progress_time, executed_time, update_time FROM planned_wayline WHERE flight_id=#{flightId} FOR UPDATE")
    PlannedWaylineEntity selectByFlightIdForUpdate(@Param("flightId") String flightId);
}
