USE `cloud_sample`;

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

CALL add_planned_wayline_column_if_missing('kmz_url', '`kmz_url` varchar(1024) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT ''Generated KMZ file download URL.'' AFTER `published_wayline_id`');
CALL add_planned_wayline_column_if_missing('kmz_md5', '`kmz_md5` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT ''Generated KMZ file MD5 fingerprint.'' AFTER `kmz_url`');
CALL add_planned_wayline_column_if_missing('kmz_object_key', '`kmz_object_key` varchar(512) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT ''Generated KMZ object key.'' AFTER `kmz_md5`');
CALL add_planned_wayline_column_if_missing('file_generated_time', '`file_generated_time` bigint DEFAULT NULL COMMENT ''The time when KMZ file was generated.'' AFTER `kmz_object_key`');
CALL add_planned_wayline_column_if_missing('flight_id', '`flight_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT ''DJI flight task id.'' AFTER `file_generated_time`');
CALL add_planned_wayline_column_if_missing('dock_sn', '`dock_sn` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT ''Target dock sn.'' AFTER `flight_id`');
CALL add_planned_wayline_column_if_missing('drone_sn', '`drone_sn` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT ''Target drone sn.'' AFTER `dock_sn`');
CALL add_planned_wayline_column_if_missing('task_status', '`task_status` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT ''Planned wayline task status.'' AFTER `drone_sn`');
CALL add_planned_wayline_column_if_missing('task_status_reason', '`task_status_reason` varchar(512) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT ''Task status reason or failure message.'' AFTER `task_status`');
CALL add_planned_wayline_column_if_missing('task_progress', '`task_progress` int DEFAULT NULL COMMENT ''Task execution progress.'' AFTER `task_status_reason`');
CALL add_planned_wayline_column_if_missing('prepared_time', '`prepared_time` bigint DEFAULT NULL COMMENT ''The time when task was prepared.'' AFTER `task_progress`');
CALL add_planned_wayline_column_if_missing('executed_time', '`executed_time` bigint DEFAULT NULL COMMENT ''The time when task started execution.'' AFTER `prepared_time`');

DROP PROCEDURE IF EXISTS add_planned_wayline_column_if_missing;
