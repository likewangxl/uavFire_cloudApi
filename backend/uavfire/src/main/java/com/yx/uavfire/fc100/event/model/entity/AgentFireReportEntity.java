package com.yx.uavfire.fc100.event.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** Immutable authenticated Agent report. Rows are never updated after insert. */
@Data
@TableName("agent_fire_report")
public class AgentFireReportEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long fireEventId;
    private String eventId;
    private Long sequence;
    private String payloadSha256;
    private String rawPayload;
    private String agentId;
    private String droneSn;
    private String taskId;
    private String agentSessionId;
    private String state;
    private String detectionKind;
    private String locationStatus;
    private String flightStatus;
    private String modelVersion;
    private String modelHash;
    private String policyVersion;
    private Integer inputSize;
    private String runtime;
    private Long sourceGeneration;
    private Long coordinatorGeneration;
    private Long eventTimestamp;
    private Integer notificationVersion;
    private Integer notificationQueued;
    private String status;
    private Long receivedTime;
}
