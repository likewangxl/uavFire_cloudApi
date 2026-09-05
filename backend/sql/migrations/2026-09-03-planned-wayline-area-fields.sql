USE `cloud_sample`;

DROP PROCEDURE IF EXISTS add_planned_wayline_area_column_if_missing;

DELIMITER $$
CREATE PROCEDURE add_planned_wayline_area_column_if_missing(
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

CALL add_planned_wayline_area_column_if_missing(
  'route_kind',
  '`route_kind` varchar(16) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT ''waypoint'' COMMENT ''waypoint/patrol/area.'' AFTER `max_speed`'
);
CALL add_planned_wayline_area_column_if_missing(
  'area_polygon_json',
  '`area_polygon_json` mediumtext CHARACTER SET utf8 COLLATE utf8_general_ci COMMENT ''Area boundary vertices in GCJ02 and WGS84.'' AFTER `route_kind`'
);
CALL add_planned_wayline_area_column_if_missing(
  'area_camera_key',
  '`area_camera_key` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT ''Camera preset used by mapping2d.'' AFTER `area_polygon_json`'
);
CALL add_planned_wayline_area_column_if_missing(
  'area_front_overlap',
  '`area_front_overlap` int DEFAULT NULL COMMENT ''mapping2d forward overlap percent.'' AFTER `area_camera_key`'
);
CALL add_planned_wayline_area_column_if_missing(
  'area_side_overlap',
  '`area_side_overlap` int DEFAULT NULL COMMENT ''mapping2d side overlap percent.'' AFTER `area_front_overlap`'
);
CALL add_planned_wayline_area_column_if_missing(
  'area_heading_deg',
  '`area_heading_deg` double DEFAULT NULL COMMENT ''mapping2d direction, north clockwise degrees.'' AFTER `area_side_overlap`'
);

DROP PROCEDURE IF EXISTS add_planned_wayline_area_column_if_missing;
