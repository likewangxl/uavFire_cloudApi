USE `cloud_sample`;

-- Guarded column helper keeps this migration rerunnable on installations whose
-- earlier fire-event migrations were applied at different times.
DROP PROCEDURE IF EXISTS `uavfire_add_column_if_missing`;
DELIMITER $$
CREATE PROCEDURE `uavfire_add_column_if_missing`(
  IN p_table varchar(64), IN p_column varchar(64), IN p_definition text)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = p_table AND column_name = p_column
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

DROP PROCEDURE IF EXISTS `uavfire_add_index_if_missing`;
DELIMITER $$
CREATE PROCEDURE `uavfire_add_index_if_missing`(
  IN p_table varchar(64), IN p_index varchar(64), IN p_definition text)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = p_table AND index_name = p_index
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD ', p_definition);
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

-- flight_id is the immutable Agent task identity. A unique current-read target
-- avoids ambiguous bindings and keeps the sequence-1 lock narrow.
CALL uavfire_add_index_if_missing('planned_wayline','uk_planned_wayline_flight_id',
  'UNIQUE KEY `uk_planned_wayline_flight_id` (`flight_id`)');

CALL uavfire_add_column_if_missing('fire_event','detection_kind',
  'varchar(16) NULL COMMENT ''FIRE / SMOKE'' AFTER `notification_version`');
CALL uavfire_add_column_if_missing('fire_event','detection_status',
  'varchar(32) NULL COMMENT ''Agent closed-loop state'' AFTER `detection_kind`');
CALL uavfire_add_column_if_missing('fire_event','location_status',
  'varchar(32) NULL COMMENT ''LASER_LOCATING / PRECISE / DEGRADED_OSD'' AFTER `detection_status`');
CALL uavfire_add_column_if_missing('fire_event','flight_status',
  'varchar(32) NULL COMMENT ''Agent observed flight state'' AFTER `location_status`');
CALL uavfire_add_column_if_missing('fire_event','agent_id','varchar(128) NULL AFTER `flight_status`');
CALL uavfire_add_column_if_missing('fire_event','agent_session_id','varchar(128) NULL AFTER `agent_id`');
CALL uavfire_add_column_if_missing('fire_event','agent_task_id','varchar(128) NULL AFTER `agent_session_id`');
CALL uavfire_add_column_if_missing('fire_event','last_agent_sequence','bigint NULL AFTER `agent_task_id`');
CALL uavfire_add_column_if_missing('fire_event','model_version','varchar(128) NULL AFTER `last_agent_sequence`');
CALL uavfire_add_column_if_missing('fire_event','model_hash','char(64) NULL AFTER `model_version`');
CALL uavfire_add_column_if_missing('fire_event','policy_version','varchar(128) NULL AFTER `model_hash`');
CALL uavfire_add_column_if_missing('fire_event','input_size','int NULL AFTER `policy_version`');
CALL uavfire_add_column_if_missing('fire_event','runtime','varchar(32) NULL AFTER `input_size`');
CALL uavfire_add_column_if_missing('fire_event','source_generation','bigint NULL AFTER `runtime`');
CALL uavfire_add_column_if_missing('fire_event','coordinator_generation','bigint NULL AFTER `source_generation`');
CALL uavfire_add_column_if_missing('fire_event','visible_roi','text NULL AFTER `coordinator_generation`');
CALL uavfire_add_column_if_missing('fire_event','agent_spatial_merge_eligible',
  'tinyint NOT NULL DEFAULT 0 AFTER `visible_roi`');

-- Agent degraded reports deliberately have no ground-fire point. This changes
-- only nullability; it never copies or reinterprets existing legacy values.
ALTER TABLE `fire_event`
  MODIFY COLUMN `lat` double NULL,
  MODIFY COLUMN `lng` double NULL;

CALL uavfire_add_column_if_missing('fire_event_history','agent_sequence','bigint NULL AFTER `action`');
CALL uavfire_add_column_if_missing('fire_event_history','detection_kind','varchar(16) NULL AFTER `agent_sequence`');
CALL uavfire_add_column_if_missing('fire_event_history','detection_status','varchar(32) NULL AFTER `detection_kind`');
CALL uavfire_add_column_if_missing('fire_event_history','location_status','varchar(32) NULL AFTER `detection_status`');
CALL uavfire_add_column_if_missing('fire_event_history','flight_status','varchar(32) NULL AFTER `location_status`');
CALL uavfire_add_column_if_missing('fire_event_history','agent_session_id','varchar(128) NULL AFTER `flight_status`');
CALL uavfire_add_column_if_missing('fire_event_history','notification_version','int NULL AFTER `agent_session_id`');
ALTER TABLE `fire_event_history`
  MODIFY COLUMN `lat` double NULL,
  MODIFY COLUMN `lng` double NULL,
  MODIFY COLUMN `action` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL;

CREATE TABLE IF NOT EXISTS `agent_fire_report` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `fire_event_id` bigint unsigned NOT NULL,
  `event_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `sequence` bigint NOT NULL,
  `payload_sha256` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `raw_payload` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `agent_id` varchar(128) NOT NULL,
  `drone_sn` varchar(128) NOT NULL,
  `task_id` varchar(128) NOT NULL,
  `agent_session_id` varchar(128) NOT NULL,
  `state` varchar(32) NOT NULL,
  `detection_kind` varchar(16) NOT NULL,
  `location_status` varchar(32) NULL,
  `flight_status` varchar(32) NULL,
  `model_version` varchar(128) NOT NULL,
  `model_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `policy_version` varchar(128) NOT NULL,
  `input_size` int NOT NULL,
  `runtime` varchar(32) NOT NULL,
  `source_generation` bigint NOT NULL,
  `coordinator_generation` bigint NOT NULL,
  `event_timestamp` bigint NOT NULL,
  `notification_version` int NOT NULL,
  `notification_queued` tinyint NOT NULL DEFAULT 0,
  `status` varchar(32) NOT NULL,
  `received_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_fire_report_event_sequence` (`event_id`,`sequence`),
  KEY `idx_agent_fire_report_parent` (`fire_event_id`,`sequence`),
  KEY `idx_agent_fire_report_received` (`received_time`),
  CONSTRAINT `fk_agent_fire_report_event` FOREIGN KEY (`fire_event_id`) REFERENCES `fire_event` (`id`),
  CONSTRAINT `chk_agent_fire_report_sequence` CHECK (`sequence` > 0),
  CONSTRAINT `chk_agent_fire_report_notification` CHECK (`notification_queued` IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Immutable authenticated Agent fire reports';

DROP PROCEDURE IF EXISTS `uavfire_add_column_if_missing`;
DROP PROCEDURE IF EXISTS `uavfire_add_index_if_missing`;
