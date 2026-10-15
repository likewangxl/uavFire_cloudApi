USE `cloud_sample`;

ALTER TABLE `fc100_fire_mission`
  ADD COLUMN `delivery_intent_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL
    COMMENT 'local id written before an external delivery create' AFTER `dji_task_id`,
  ADD COLUMN `delivery_intent_status` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL
    COMMENT 'PENDING / FAILED / EXTERNAL_CREATED / LOCAL_COMMIT_FAILED' AFTER `delivery_intent_id`;
