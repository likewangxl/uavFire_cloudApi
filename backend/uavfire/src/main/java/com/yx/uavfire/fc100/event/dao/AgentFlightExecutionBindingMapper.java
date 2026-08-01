package com.yx.uavfire.fc100.event.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yx.uavfire.fc100.event.model.entity.AgentFlightExecutionBindingEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentFlightExecutionBindingMapper extends BaseMapper<AgentFlightExecutionBindingEntity> {
    @Select("SELECT id,flight_id,planned_wayline_pk,planned_wayline_id,workspace_id,assigned_drone_sn," +
        "prepared_at,execution_started_at,terminal_at,execution_status,terminal_status,create_time,update_time " +
        "FROM agent_flight_execution_binding WHERE flight_id=#{flightId} FOR UPDATE")
    AgentFlightExecutionBindingEntity selectByFlightIdForUpdate(@Param("flightId") String flightId);

    @Update("UPDATE agent_flight_execution_binding SET assigned_drone_sn=#{droneSn}, " +
        "execution_started_at=COALESCE(execution_started_at,#{startedAt}), execution_status='executing', " +
        "update_time=#{startedAt} WHERE flight_id=#{flightId} AND terminal_at IS NULL")
    int markStarted(@Param("flightId") String flightId, @Param("droneSn") String droneSn,
                    @Param("startedAt") long startedAt);

    @Update("UPDATE agent_flight_execution_binding SET execution_status=#{status}, update_time=#{at} " +
        "WHERE flight_id=#{flightId} AND terminal_at IS NULL")
    int markActive(@Param("flightId") String flightId, @Param("status") String status, @Param("at") long at);

    @Update("UPDATE agent_flight_execution_binding SET terminal_at=#{at}, terminal_status=#{status}, " +
        "execution_status=#{status}, update_time=#{at} WHERE flight_id=#{flightId} AND terminal_at IS NULL")
    int markTerminal(@Param("flightId") String flightId, @Param("status") String status, @Param("at") long at);
}
