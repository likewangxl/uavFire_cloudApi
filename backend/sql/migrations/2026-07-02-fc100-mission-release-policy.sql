USE `cloud_sample`;

ALTER TABLE `fc100_fire_mission`
  ADD COLUMN `release_policy` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'MANUAL_CONFIRM'
    COMMENT 'MANUAL_CONFIRM / DRY_RUN / CONTROLLED_TEST_AUTO' AFTER `latest_route_file_id`,
  ADD COLUMN `release_execution_mode` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'OFFICIAL_HOOK_MANUAL'
    COMMENT 'OFFICIAL_HOOK_MANUAL / DELIVERY_SYNC_REMOTE / PSDK_RELEASE' AFTER `release_policy`;
