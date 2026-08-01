package com.yx.uavfire.fc100.event.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yx.uavfire.fc100.event.model.entity.AgentFireReportEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentFireReportMapper extends BaseMapper<AgentFireReportEntity> {
    @Select("SELECT id, fire_event_id, event_id, sequence, payload_sha256, raw_payload, agent_id, " +
        "drone_sn, task_id, agent_session_id, state, detection_kind, location_status, flight_status, " +
        "model_version, model_hash, policy_version, input_size, runtime, source_generation, " +
        "coordinator_generation, event_timestamp, notification_version, notification_queued, status, received_time " +
        "FROM agent_fire_report WHERE event_id=#{eventId} AND sequence=#{sequence}")
    AgentFireReportEntity selectByEventAndSequence(@Param("eventId") String eventId,
                                                    @Param("sequence") long sequence);

    @Select("SELECT id, fire_event_id, event_id, sequence, payload_sha256, raw_payload, agent_id, " +
        "drone_sn, task_id, agent_session_id, state, detection_kind, location_status, flight_status, " +
        "model_version, model_hash, policy_version, input_size, runtime, source_generation, " +
        "coordinator_generation, event_timestamp, notification_version, notification_queued, status, received_time " +
        "FROM agent_fire_report WHERE event_id=#{eventId} AND sequence=#{sequence} FOR UPDATE")
    AgentFireReportEntity selectByEventAndSequenceForUpdate(@Param("eventId") String eventId,
                                                             @Param("sequence") long sequence);

    @Insert("INSERT INTO agent_fire_report (fire_event_id,event_id,sequence,payload_sha256,raw_payload," +
        "agent_id,drone_sn,task_id,agent_session_id,state,detection_kind,location_status,flight_status," +
        "model_version,model_hash,policy_version,input_size,runtime,source_generation,coordinator_generation," +
        "event_timestamp,notification_version,notification_queued,status,received_time) VALUES " +
        "(#{fireEventId},#{eventId},#{sequence},#{payloadSha256},#{rawPayload},#{agentId},#{droneSn}," +
        "#{taskId},#{agentSessionId},#{state},#{detectionKind},#{locationStatus},#{flightStatus}," +
        "#{modelVersion},#{modelHash},#{policyVersion},#{inputSize},#{runtime},#{sourceGeneration}," +
        "#{coordinatorGeneration},#{eventTimestamp},#{notificationVersion},#{notificationQueued},#{status},#{receivedTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertImmutable(AgentFireReportEntity row);
}
