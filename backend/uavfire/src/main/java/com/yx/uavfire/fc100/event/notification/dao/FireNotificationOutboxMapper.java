package com.yx.uavfire.fc100.event.notification.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yx.uavfire.fc100.event.notification.model.FireNotificationOutboxEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface FireNotificationOutboxMapper extends BaseMapper<FireNotificationOutboxEntity> {
    String COLUMNS = "id,event_id,notification_version,notification_id,workspace_id,notification_type," +
        "payload_sha256,payload,status,attempts,next_attempt_time,lease_token,lease_expires_at,last_error," +
        "sent_time,create_time,update_time";

    @Select("SELECT " + COLUMNS + " FROM fire_notification_outbox " +
        "WHERE event_id=#{eventId} AND notification_version=#{version}")
    FireNotificationOutboxEntity selectIdentity(@Param("eventId") String eventId,
                                                @Param("version") int version);

    @Select("SELECT " + COLUMNS + " FROM fire_notification_outbox " +
        "WHERE event_id=#{eventId} AND notification_version=#{version} FOR UPDATE")
    FireNotificationOutboxEntity selectIdentityForUpdate(@Param("eventId") String eventId,
                                                         @Param("version") int version);

    @Insert("INSERT INTO fire_notification_outbox(event_id,notification_version,notification_id,workspace_id," +
        "notification_type,payload_sha256,payload,status,attempts,next_attempt_time,create_time,update_time) VALUES(" +
        "#{eventId},#{notificationVersion},#{notificationId},#{workspaceId},#{notificationType},#{payloadSha256}," +
        "#{payload},#{status},#{attempts},#{nextAttemptTime},#{createTime},#{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertOutbox(FireNotificationOutboxEntity row);

    @Select("SELECT " + COLUMNS + " FROM fire_notification_outbox WHERE " +
        "(status='PENDING' AND next_attempt_time<=#{now}) OR " +
        "(status='IN_FLIGHT' AND lease_expires_at<=#{now}) " +
        "ORDER BY next_attempt_time,id LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<FireNotificationOutboxEntity> selectEligibleForClaim(@Param("now") long now,
                                                             @Param("limit") int limit);

    @Update("UPDATE fire_notification_outbox SET status='IN_FLIGHT',attempts=attempts+1," +
        "lease_token=#{token},lease_expires_at=#{expiresAt},update_time=#{now} WHERE id=#{id} AND " +
        "((status='PENDING' AND next_attempt_time<=#{now}) OR " +
        "(status='IN_FLIGHT' AND lease_expires_at<=#{now}))")
    int claim(@Param("id") long id, @Param("token") String token,
              @Param("expiresAt") long expiresAt, @Param("now") long now);

    @Update("UPDATE fire_notification_outbox SET status='SENT',sent_time=#{now},update_time=#{now}," +
        "lease_token=NULL,lease_expires_at=NULL,last_error=NULL WHERE id=#{id} AND status='IN_FLIGHT' " +
        "AND lease_token=#{token}")
    int completeSuccess(@Param("id") long id, @Param("token") String token, @Param("now") long now);

    @Update("UPDATE fire_notification_outbox SET status='PENDING',next_attempt_time=#{nextAttempt}," +
        "update_time=#{now},lease_token=NULL,lease_expires_at=NULL,last_error=#{error} " +
        "WHERE id=#{id} AND status='IN_FLIGHT' AND lease_token=#{token}")
    int completeFailure(@Param("id") long id, @Param("token") String token,
                        @Param("nextAttempt") long nextAttempt, @Param("now") long now,
                        @Param("error") String error);
}
