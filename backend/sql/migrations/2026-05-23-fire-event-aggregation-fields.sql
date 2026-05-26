USE `cloud_sample`;

ALTER TABLE `fire_event`
  ADD COLUMN `last_seen_time` bigint NULL COMMENT '最近一次识别命中时刻 (ms epoch)' AFTER `event_timestamp`,
  ADD COLUMN `report_count` int NOT NULL DEFAULT 1 COMMENT '同一火情聚合命中次数' AFTER `last_seen_time`,
  ADD COLUMN `last_source_event_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '最近一次 AI 原始事件编号' AFTER `report_count`,
  ADD COLUMN `notification_version` int NOT NULL DEFAULT 1 COMMENT '事件级通知版本，风险升级时递增' AFTER `last_source_event_id`;

UPDATE `fire_event`
SET
  `last_seen_time` = COALESCE(`last_seen_time`, `event_timestamp`),
  `report_count` = CASE WHEN `report_count` IS NULL OR `report_count` < 1 THEN 1 ELSE `report_count` END,
  `last_source_event_id` = COALESCE(`last_source_event_id`, `event_id`),
  `notification_version` = CASE WHEN `notification_version` IS NULL OR `notification_version` < 1 THEN 1 ELSE `notification_version` END;

ALTER TABLE `fire_event`
  MODIFY COLUMN `last_seen_time` bigint NOT NULL COMMENT '最近一次识别命中时刻 (ms epoch)',
  ADD KEY `idx_fire_event_merge` (`workspace_id`,`device_sn`,`deleted`,`last_seen_time`);
