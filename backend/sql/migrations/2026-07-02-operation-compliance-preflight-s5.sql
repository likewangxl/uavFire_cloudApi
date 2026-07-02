USE `cloud_sample`;

CREATE TABLE IF NOT EXISTS `operation_compliance_record` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `incident_id` bigint unsigned DEFAULT NULL,
  `record_type` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT 'PREFLIGHT_CHECK',
  `status` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT 'PASS / BLOCK',
  `operator_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `blocking_items_json` longtext COMMENT 'preflight blocking rule details',
  `details_json` longtext COMMENT 'all preflight rule results',
  `create_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_operation_compliance_incident` (`incident_id`,`create_time`),
  KEY `idx_operation_compliance_type_status` (`record_type`,`status`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='S5 preflight compliance evidence records';

CREATE TABLE IF NOT EXISTS `operation_flight_application_record` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `incident_id` bigint unsigned NOT NULL,
  `application_no` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `approval_no` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `valid_from` bigint DEFAULT NULL,
  `valid_to` bigint DEFAULT NULL,
  `material_url` text,
  `operator_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `create_time` bigint NOT NULL,
  `update_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_operation_flight_application_incident` (`incident_id`,`valid_from`,`valid_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='manual flight application approval evidence record only';

CREATE TABLE IF NOT EXISTS `operation_takeoff_confirmation_record` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `incident_id` bigint unsigned NOT NULL,
  `operator_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `confirmation_no` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `material_url` text,
  `confirmed_at` bigint NOT NULL,
  `create_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_operation_takeoff_confirmation_incident` (`incident_id`,`operator_id`,`confirmed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='manual takeoff confirmation evidence record only';

CREATE TABLE IF NOT EXISTS `operation_landing_report_record` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `incident_id` bigint unsigned NOT NULL,
  `report_no` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `material_url` text,
  `operator_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `landed_at` bigint NOT NULL,
  `create_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_operation_landing_report_incident` (`incident_id`,`landed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='manual landing report evidence record only';

CREATE TABLE IF NOT EXISTS `operation_qualification_record` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `qualification_type` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT 'CLUSTER_FLIGHT_PERMIT / AIRDROP_APPROVAL / AIRWORTHINESS / JOINT_OPERATION_AGREEMENT',
  `qualification_no` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `issuer` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `valid_from` bigint DEFAULT NULL,
  `valid_to` bigint DEFAULT NULL,
  `material_url` text,
  `status` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'VALID',
  `operator_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `create_time` bigint NOT NULL,
  `update_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_operation_qualification_type_status` (`qualification_type`,`status`,`valid_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='manual operation qualification evidence record only';
