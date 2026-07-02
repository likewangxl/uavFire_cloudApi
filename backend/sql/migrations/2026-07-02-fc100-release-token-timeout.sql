USE `cloud_sample`;

ALTER TABLE `fc100_fire_mission`
  ADD COLUMN `release_confirmation_token` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL
    COMMENT 'system-issued one-time token for PAYLOAD_RELEASE_PENDING confirmation' AFTER `release_execution_mode`,
  ADD COLUMN `release_pending_started_at` bigint DEFAULT NULL
    COMMENT 'PAYLOAD_RELEASE_PENDING entered time, ms epoch' AFTER `release_confirmation_token`,
  ADD COLUMN `release_token_expires_at` bigint DEFAULT NULL
    COMMENT 'release confirmation token expiry, ms epoch' AFTER `release_pending_started_at`,
  ADD COLUMN `release_token_used_at` bigint DEFAULT NULL
    COMMENT 'one-time release confirmation token consume time, ms epoch' AFTER `release_token_expires_at`;
