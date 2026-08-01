package com.yx.uavfire.fc100.event.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FireEventMapper extends BaseMapper<FireEventEntity> {

    @Select("SELECT GET_LOCK(#{name}, #{timeoutSeconds})")
    Integer acquireNamedLock(@Param("name") String name, @Param("timeoutSeconds") int timeoutSeconds);

    @Select("SELECT RELEASE_LOCK(#{name})")
    Integer releaseNamedLock(@Param("name") String name);

    @Select("SELECT * FROM fire_event WHERE event_id=#{eventId}")
    FireEventEntity selectByEventId(@Param("eventId") String eventId);

    @Select("SELECT * FROM fire_event WHERE event_id=#{eventId} FOR UPDATE")
    FireEventEntity selectByEventIdForUpdate(@Param("eventId") String eventId);

    @Update("UPDATE fire_event SET last_agent_sequence=#{nextSequence}, detection_status=#{state}, " +
        "location_status=COALESCE(#{locationStatus},location_status), " +
        "flight_status=COALESCE(#{flightStatus},flight_status), update_time=#{updateTime} " +
        "WHERE event_id=#{eventId} AND last_agent_sequence=#{expectedSequence}")
    int advanceAgentSequence(@Param("eventId") String eventId,
                             @Param("expectedSequence") long expectedSequence,
                             @Param("nextSequence") long nextSequence,
                             @Param("state") String state,
                             @Param("locationStatus") String locationStatus,
                             @Param("flightStatus") String flightStatus,
                             @Param("updateTime") long updateTime);
}
