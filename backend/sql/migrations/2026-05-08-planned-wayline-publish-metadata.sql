USE `cloud_sample`;

SET @publisher_column_exists := (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'planned_wayline'
    AND COLUMN_NAME = 'publisher'
);

SET @publisher_sql := IF(
  @publisher_column_exists = 0,
  'ALTER TABLE `planned_wayline` ADD COLUMN `publisher` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT ''The name of the publisher.'' AFTER `creator`',
  'SELECT ''planned_wayline.publisher already exists'' AS message'
);
PREPARE publisher_stmt FROM @publisher_sql;
EXECUTE publisher_stmt;
DEALLOCATE PREPARE publisher_stmt;

SET @publish_time_column_exists := (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'planned_wayline'
    AND COLUMN_NAME = 'publish_time'
);

SET @publish_time_sql := IF(
  @publish_time_column_exists = 0,
  'ALTER TABLE `planned_wayline` ADD COLUMN `publish_time` bigint DEFAULT NULL COMMENT ''The time when this planned wayline was published.'' AFTER `publisher`',
  'SELECT ''planned_wayline.publish_time already exists'' AS message'
);
PREPARE publish_time_stmt FROM @publish_time_sql;
EXECUTE publish_time_stmt;
DEALLOCATE PREPARE publish_time_stmt;
