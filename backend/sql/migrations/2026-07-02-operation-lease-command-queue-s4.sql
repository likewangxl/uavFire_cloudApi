USE `cloud_sample`;

ALTER TABLE `operation_resource_lease`
  ADD COLUMN `active_resource_sn` varchar(64)
    GENERATED ALWAYS AS (case when `status` = 'ACTIVE' then `resource_sn` else NULL end) STORED
    COMMENT 'S4 unique key: only ACTIVE resource leases participate',
  ADD COLUMN `active_delivery_owner_id` bigint unsigned
    GENERATED ALWAYS AS (case when `status` = 'ACTIVE' and `lease_type` = 'DELIVERY_PRIMARY' then `owner_id` else NULL end) STORED
    COMMENT 'S4 unique key: one active DELIVERY_PRIMARY lease per incident owner',
  ADD UNIQUE KEY `UNI_OPERATION_LEASE_ACTIVE_RESOURCE` (`active_resource_sn`),
  ADD UNIQUE KEY `UNI_OPERATION_LEASE_ACTIVE_DELIVERY_OWNER` (`active_delivery_owner_id`),
  ADD KEY `idx_operation_lease_expire` (`status`,`expires_at`);

ALTER TABLE `operation_command_event`
  ADD COLUMN `mission_no` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL
    COMMENT 'mission number for audit replay filters' AFTER `command_type`,
  ADD COLUMN `operator_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL
    COMMENT 'operator who enqueued the command' AFTER `idempotency_key`,
  ADD COLUMN `sent_at` bigint DEFAULT NULL COMMENT 'last send timestamp' AFTER `retry_count`,
  ADD COLUMN `next_attempt_at` bigint DEFAULT NULL COMMENT 'next retry timestamp' AFTER `ack_at`,
  ADD COLUMN `update_time` bigint DEFAULT NULL COMMENT 'last state update timestamp' AFTER `create_time`,
  DROP KEY `idx_operation_command_idempotency`,
  ADD UNIQUE KEY `UNI_OPERATION_COMMAND_IDEMPOTENCY` (`idempotency_key`),
  ADD KEY `idx_operation_command_mission` (`mission_no`,`create_time`),
  ADD KEY `idx_operation_command_due` (`status`,`next_attempt_at`,`create_time`);
