package com.yx.uavfire.fc100.event.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("agent_flight_execution_binding")
public class AgentFlightExecutionBindingEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String flightId;
    private Integer plannedWaylinePk;
    private String plannedWaylineId;
    private String workspaceId;
    private String assignedDroneSn;
    private Long preparedAt;
    private Long executionStartedAt;
    private Long terminalAt;
    private String executionStatus;
    private String terminalStatus;
    private Long createTime;
    private Long updateTime;
}
