USE `cloud_sample`;

ALTER TABLE `fire_event`
  ADD COLUMN `confirmed_status` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'PENDING'
    COMMENT 'PENDING / CONFIRMED / REJECTED' AFTER `status`,
  ADD COLUMN `linked_incident_id` bigint unsigned DEFAULT NULL
    COMMENT 'linked operation_incident.id after manual confirmation' AFTER `confirmed_status`,
  ADD KEY `idx_fire_event_confirmed` (`confirmed_status`,`update_time`),
  ADD KEY `idx_fire_event_incident` (`linked_incident_id`);

ALTER TABLE `fc100_fire_mission`
  ADD COLUMN `incident_id` bigint unsigned DEFAULT NULL
    COMMENT 'linked operation_incident.id for orchestration layer' AFTER `fire_event_id`,
  ADD KEY `idx_fc100_mission_incident` (`incident_id`);

CREATE TABLE `operation_incident` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `incident_no` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `fire_event_id` bigint unsigned NOT NULL,
  `level` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT 'risk level from fire_event.fire_level or manual override',
  `status` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT 'CANDIDATE / CONFIRMED / DISPATCHING / RESPONDING / RECHECKING / RESOLVED / FALSE_ALARM / ABORTED / ARCHIVED',
  `center_lat` double DEFAULT NULL,
  `center_lng` double DEFAULT NULL,
  `risk_radius_m` double DEFAULT NULL,
  `created_by` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `confirmed_by` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `closed_at` bigint DEFAULT NULL,
  `create_time` bigint NOT NULL,
  `update_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UNI_OPERATION_INCIDENT_NO` (`incident_no`),
  KEY `idx_operation_incident_fire_event` (`fire_event_id`),
  KEY `idx_operation_incident_status_ct` (`status`,`create_time`),
  KEY `idx_operation_incident_level_ct` (`level`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='operation incident orchestration root';

CREATE TABLE `operation_assignment` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `incident_id` bigint unsigned NOT NULL,
  `resource_sn` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT 'device SN or personnel code',
  `role` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT 'MONITOR_PRIMARY / MONITOR_RECHECK / DELIVERY_PRIMARY / DELIVERY_BACKUP / COMMANDER',
  `status` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `lease_id` bigint unsigned DEFAULT NULL COMMENT 'operation_resource_lease.id, enabled in S4',
  `assigned_at` bigint NOT NULL,
  `released_at` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_operation_assignment_incident` (`incident_id`,`assigned_at`),
  KEY `idx_operation_assignment_role_status` (`incident_id`,`role`,`status`),
  KEY `idx_operation_assignment_resource` (`resource_sn`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='operation incident resource/personnel assignment';

CREATE TABLE `operation_resource_lease` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `resource_sn` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `lease_type` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `owner_type` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `owner_id` bigint unsigned NOT NULL,
  `expires_at` bigint DEFAULT NULL,
  `heartbeat_at` bigint DEFAULT NULL,
  `status` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_operation_lease_resource` (`resource_sn`,`status`),
  KEY `idx_operation_lease_owner` (`owner_type`,`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='operation resource lease placeholder for S4';

CREATE TABLE `operation_command_event` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `command_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `target_sn` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `command_type` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `payload_json` longtext,
  `status` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `idempotency_key` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `retry_count` int NOT NULL DEFAULT 0,
  `ack_at` bigint DEFAULT NULL,
  `error_message` text,
  `create_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UNI_OPERATION_COMMAND_ID` (`command_id`),
  KEY `idx_operation_command_target` (`target_sn`,`status`,`create_time`),
  KEY `idx_operation_command_idempotency` (`idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='operation command event queue placeholder for S4';

CREATE TABLE `operation_incident_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `incident_id` bigint unsigned NOT NULL,
  `action` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `from_status` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `to_status` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `operator_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `operator_role` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `client_ip` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `request_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `idempotency_key` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `remark` text,
  `create_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_operation_incident_log_ct` (`incident_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='operation incident audit log';
