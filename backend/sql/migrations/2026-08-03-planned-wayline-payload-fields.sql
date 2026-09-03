USE `cloud_sample`;

DROP PROCEDURE IF EXISTS add_planned_wayline_payload_column_if_missing;

DELIMITER $$
CREATE PROCEDURE add_planned_wayline_payload_column_if_missing(
  IN target_column_name varchar(64),
  IN column_definition text
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'planned_wayline'
      AND COLUMN_NAME = target_column_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `planned_wayline` ADD COLUMN ', column_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

CALL add_planned_wayline_payload_column_if_missing(
  'payload_model_key',
  '`payload_model_key` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT ''DJI payload model key, for example H20T or H30T.'' AFTER `aircraft_model_key`'
);
CALL add_planned_wayline_payload_column_if_missing(
  'payload_position_index',
  '`payload_position_index` tinyint DEFAULT NULL COMMENT ''DJI gimbal position: 0 left/main, 1 right, 2 upper.'' AFTER `payload_model_key`'
);

DROP PROCEDURE IF EXISTS add_planned_wayline_payload_column_if_missing;
