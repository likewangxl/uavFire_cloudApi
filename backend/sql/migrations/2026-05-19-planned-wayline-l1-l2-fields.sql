USE `cloud_sample`;

-- L1+L2 schema 扩展:per-航线 mission 配置 + 实时任务进度跟踪
-- 详见 docs/WAYLINE_L1_L2_CONTRACT.md

DROP PROCEDURE IF EXISTS add_planned_wayline_column_if_missing;

DELIMITER $$
CREATE PROCEDURE add_planned_wayline_column_if_missing(
  IN column_name_value varchar(64),
  IN column_definition text
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'planned_wayline'
      AND COLUMN_NAME = column_name_value
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `planned_wayline` ADD COLUMN ', column_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

-- L1: 全局 mission 配置(KMZ <wpml:missionConfig> 字段)取消硬编码,允许 per 航线覆盖
CALL add_planned_wayline_column_if_missing('finish_action', '`finish_action` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT ''goHome'' COMMENT ''KMZ wpml:finishAction (goHome/autoLand/noAction/gotoFirstWaypoint).'' AFTER `max_speed`');
CALL add_planned_wayline_column_if_missing('exit_on_rc_lost', '`exit_on_rc_lost` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT ''goContinue'' COMMENT ''KMZ wpml:exitOnRCLost (goContinue/executeLostAction).'' AFTER `finish_action`');
CALL add_planned_wayline_column_if_missing('rc_lost_action', '`rc_lost_action` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT ''goBack'' COMMENT ''KMZ wpml:executeRCLostAction (hover/goBack/landing).'' AFTER `exit_on_rc_lost`');
CALL add_planned_wayline_column_if_missing('takeoff_security_height', '`takeoff_security_height` int DEFAULT 20 COMMENT ''KMZ wpml:takeOffSecurityHeight, range [1.2, 1500] m.'' AFTER `rc_lost_action`');
CALL add_planned_wayline_column_if_missing('global_transitional_speed', '`global_transitional_speed` double DEFAULT 5 COMMENT ''KMZ wpml:globalTransitionalSpeed, range [1, 15] m/s.'' AFTER `takeoff_security_height`');
CALL add_planned_wayline_column_if_missing('rth_altitude', '`rth_altitude` int DEFAULT NULL COMMENT ''Dock flighttask_prepare rth_altitude (m); null = use aircraft default.'' AFTER `global_transitional_speed`');

-- L2: 实时任务进度持久化(agent / dock 两条路径共用此组列)
CALL add_planned_wayline_column_if_missing('wayline_mission_state', '`wayline_mission_state` tinyint DEFAULT NULL COMMENT ''DJI WaylineMissionStateEnum value 0-9 (or agent mapped value).'' AFTER `task_progress`');
CALL add_planned_wayline_column_if_missing('current_waypoint_index', '`current_waypoint_index` int DEFAULT NULL COMMENT ''Current waypoint index (0-based) reported by aircraft/agent.'' AFTER `wayline_mission_state`');
CALL add_planned_wayline_column_if_missing('total_waypoints', '`total_waypoints` int DEFAULT NULL COMMENT ''Total waypoints in active mission, reported by aircraft/agent.'' AFTER `current_waypoint_index`');
CALL add_planned_wayline_column_if_missing('media_count', '`media_count` int DEFAULT NULL COMMENT ''Media files captured so far during this task.'' AFTER `total_waypoints`');
CALL add_planned_wayline_column_if_missing('break_point_json', '`break_point_json` text COMMENT ''JSON serialised break-point (wayline_id/waypoint_index/progress/state) for resume.'' AFTER `media_count`');
CALL add_planned_wayline_column_if_missing('last_progress_time', '`last_progress_time` bigint DEFAULT NULL COMMENT ''Server-side timestamp of last progress update (ms).'' AFTER `break_point_json`');

DROP PROCEDURE IF EXISTS add_planned_wayline_column_if_missing;
