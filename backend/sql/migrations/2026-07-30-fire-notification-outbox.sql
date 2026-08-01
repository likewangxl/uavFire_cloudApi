USE `cloud_sample`;

CREATE TABLE IF NOT EXISTS `fire_notification_outbox` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `event_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `notification_version` int NOT NULL,
  `notification_id` varchar(80) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `workspace_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `notification_type` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `payload_sha256` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `payload` json NOT NULL,
  `status` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `attempts` int NOT NULL DEFAULT 0,
  `next_attempt_time` bigint NOT NULL,
  `lease_token` char(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `lease_expires_at` bigint DEFAULT NULL,
  `last_error` varchar(500) DEFAULT NULL,
  `sent_time` bigint DEFAULT NULL,
  `create_time` bigint NOT NULL,
  `update_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_fire_notification_event_version` (`event_id`,`notification_version`),
  UNIQUE KEY `uk_fire_notification_id` (`notification_id`),
  KEY `idx_fire_notification_due` (`status`,`next_attempt_time`,`lease_expires_at`,`id`),
  CONSTRAINT `chk_fire_notification_version` CHECK (`notification_version` IN (1,2)),
  CONSTRAINT `chk_fire_notification_status` CHECK (`status` IN ('PENDING','IN_FLIGHT','SENT')),
  CONSTRAINT `chk_fire_notification_attempts` CHECK (`attempts` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable transactional fire WebSocket notification Outbox';
