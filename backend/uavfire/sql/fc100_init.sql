-- FC100 消防灭火系统数据库初始化
-- spec: docs/superpowers/specs/2026-05-13-fc100-fire-suppression-design.md §3
-- 合并进 M4T 后,fc100 表与 M4T 共用 cloud_sample 库。
-- 命名约定与 M4T cloud_sample.sql 一致：bigint 毫秒时间戳 / utf8mb3 / tinyint(1) 布尔 / utf8_general_ci

USE `cloud_sample`;
SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 1. fire_event — 火情事件原始记录
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `fire_event`;
CREATE TABLE `fire_event` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `event_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT 'M4T 上报的事件唯一编号',
  `workspace_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'DEFAULT',
  `source` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT 'M4T / MANUAL / TEST',
  `device_sn` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `confidence` decimal(5,4) NOT NULL COMMENT '0.0000-1.0000',
  `fire_level` varchar(16) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT 'LOW / MEDIUM / HIGH / UNKNOWN',
  `lat` double NOT NULL,
  `lng` double NOT NULL,
  `alt` double DEFAULT NULL,
  `altitude_reference` varchar(16) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'ELLIPSOID' COMMENT 'ELLIPSOID / AGL / ASL',
  `geo_method` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '火点定位方法, 如 RAY_DEM_RTK',
  `geo_error_radius_m` double DEFAULT NULL COMMENT '火点定位误差半径(米)',
  `geo_quality` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT 'AUTO_WAYPOINT_READY / DEM_MISSING / RTK_NOT_FIXED / LOW_ACCURACY',
  `geo_source_ts` bigint DEFAULT NULL COMMENT '定位快照源时间戳(ms epoch)',
  `aircraft_lat` double DEFAULT NULL COMMENT '检测时飞机纬度',
  `aircraft_lng` double DEFAULT NULL COMMENT '检测时飞机经度',
  `aircraft_alt` double DEFAULT NULL COMMENT '检测时飞机高程',
  `gimbal_pitch` double DEFAULT NULL COMMENT '检测时云台 pitch',
  `gimbal_yaw` double DEFAULT NULL COMMENT '检测时云台 yaw',
  `gimbal_roll` double DEFAULT NULL COMMENT '检测时云台 roll',
  `thermal_roi` text COMMENT '热区 ROI JSON',
  `thermal_temperature` double DEFAULT NULL,
  `temperature_unit` varchar(8) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'K' COMMENT 'K / C',
  `thermal_image_url` text,
  `visible_image_url` text,
  `evidence_sha256` char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT 'agent原始JPEG SHA-256',
  `evidence_captured_at` bigint DEFAULT NULL COMMENT 'agent证据图采集时刻(ms epoch)',
  `model_sha256` char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '推理模型SHA-256',
  `event_timestamp` bigint NOT NULL COMMENT 'M4T 检测时刻 (ms epoch)',
  `last_seen_time` bigint NOT NULL COMMENT '最近一次识别命中时刻 (ms epoch)',
  `report_count` int NOT NULL DEFAULT 1 COMMENT '同一火情聚合命中次数',
  `last_source_event_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '最近一次 AI 原始事件编号',
  `notification_version` int NOT NULL DEFAULT 1 COMMENT '事件级通知版本，风险升级时递增',
  `status` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT 'NEW / LOW_CONFIDENCE / MISSION_CREATED / IGNORED',
  `confirmed_status` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / CONFIRMED / REJECTED',
  `linked_incident_id` bigint unsigned DEFAULT NULL COMMENT 'operation_incident.id',
  `deleted` tinyint(1) NOT NULL DEFAULT 0,
  `created_by` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `updated_by` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `create_time` bigint NOT NULL,
  `update_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UNI_EVENT_ID` (`event_id`),
  KEY `idx_workspace_status` (`workspace_id`,`status`),
  KEY `idx_event_timestamp` (`event_timestamp`),
  KEY `idx_fire_event_confirmed` (`confirmed_status`,`update_time`),
  KEY `idx_fire_event_incident` (`linked_incident_id`),
  KEY `idx_fire_event_merge` (`workspace_id`,`device_sn`,`deleted`,`last_seen_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='火情事件';

-- ---------------------------------------------------------------------------
-- 1.1 fire_event_history — 火情事件命中历史快照
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `fire_event_history`;
CREATE TABLE `fire_event_history` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `fire_event_id` bigint unsigned NOT NULL COMMENT '父火情事件 ID',
  `event_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT '父火情事件编号',
  `source_event_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT '本次 AI 原始事件编号',
  `workspace_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'DEFAULT',
  `source` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `device_sn` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `confidence` decimal(5,4) NOT NULL,
  `fire_level` varchar(16) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `lat` double NOT NULL,
  `lng` double NOT NULL,
  `alt` double DEFAULT NULL,
  `altitude_reference` varchar(16) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `geo_method` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '火点定位方法, 如 RAY_DEM_RTK',
  `geo_error_radius_m` double DEFAULT NULL COMMENT '火点定位误差半径(米)',
  `geo_quality` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT 'AUTO_WAYPOINT_READY / DEM_MISSING / RTK_NOT_FIXED / LOW_ACCURACY',
  `geo_source_ts` bigint DEFAULT NULL COMMENT '定位快照源时间戳(ms epoch)',
  `aircraft_lat` double DEFAULT NULL COMMENT '检测时飞机纬度',
  `aircraft_lng` double DEFAULT NULL COMMENT '检测时飞机经度',
  `aircraft_alt` double DEFAULT NULL COMMENT '检测时飞机高程',
  `gimbal_pitch` double DEFAULT NULL COMMENT '检测时云台 pitch',
  `gimbal_yaw` double DEFAULT NULL COMMENT '检测时云台 yaw',
  `gimbal_roll` double DEFAULT NULL COMMENT '检测时云台 roll',
  `thermal_roi` text COMMENT '热区 ROI JSON',
  `thermal_temperature` double DEFAULT NULL,
  `temperature_unit` varchar(8) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `thermal_image_url` text,
  `visible_image_url` text,
  `evidence_sha256` char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT 'agent原始JPEG SHA-256',
  `evidence_captured_at` bigint DEFAULT NULL COMMENT 'agent证据图采集时刻(ms epoch)',
  `model_sha256` char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '推理模型SHA-256',
  `event_timestamp` bigint NOT NULL,
  `action` varchar(16) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT 'CREATED / MERGED',
  `create_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_fire_event_history_parent` (`fire_event_id`,`event_timestamp`),
  KEY `idx_fire_event_history_source` (`source_event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='火情事件命中历史快照';

-- ---------------------------------------------------------------------------
-- 2. fc100_fire_mission — 灭火任务主表 + 状态机
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `fc100_fire_mission`;
CREATE TABLE `fc100_fire_mission` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `mission_no` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT 'MISSION-yyyyMMdd-HHmmss-NNNN',
  `workspace_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'DEFAULT',
  `fire_event_id` bigint unsigned NOT NULL,
  `incident_id` bigint unsigned DEFAULT NULL COMMENT 'operation_incident.id',
  `parent_mission_id` bigint unsigned DEFAULT NULL COMMENT 'H-2 二次投放父任务',
  `attempt_index` int NOT NULL DEFAULT 1 COMMENT 'H-2 同火情第几次出任务',
  `aircraft_sn` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `payload_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `payload_type` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT 'WINCH_FLAGSHIP / LIFTING_DUAL_BATTERY',
  `water_load_liters` double DEFAULT NULL,
  `estimated_total_weight_kg` double DEFAULT NULL,
  `takeoff_lat` double DEFAULT NULL,
  `takeoff_lng` double DEFAULT NULL,
  `takeoff_alt` double DEFAULT NULL,
  `wind_speed_at_approval` double DEFAULT NULL,
  `wind_direction_deg` double DEFAULT NULL COMMENT 'meteorological: 风从哪边来 [0,360)',
  `dji_task_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `latest_route_file_id` bigint unsigned DEFAULT NULL,
  `release_policy` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'MANUAL_CONFIRM' COMMENT 'MANUAL_CONFIRM / DRY_RUN / CONTROLLED_TEST_AUTO',
  `release_execution_mode` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'OFFICIAL_HOOK_MANUAL' COMMENT 'OFFICIAL_HOOK_MANUAL / DELIVERY_SYNC_REMOTE / PSDK_RELEASE',
  `release_confirmation_token` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT 'system-issued one-time token for PAYLOAD_RELEASE_PENDING confirmation',
  `release_pending_started_at` bigint DEFAULT NULL COMMENT 'PAYLOAD_RELEASE_PENDING entered time',
  `release_token_expires_at` bigint DEFAULT NULL COMMENT 'release confirmation token expiry',
  `release_token_used_at` bigint DEFAULT NULL COMMENT 'one-time release confirmation token consume time',
  `status` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT 'B-1 FireMissionStatus 枚举',
  `version` bigint NOT NULL DEFAULT 0 COMMENT 'B-2 乐观锁',
  `is_high_confidence` tinyint(1) NOT NULL DEFAULT 0,
  `created_by` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT 'B-6 派单人',
  `approver_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT 'B-6 审批人',
  `release_operator_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT 'B-6 投放操作员',
  `reviewer_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT 'B-6 复查员',
  `approved_at` bigint DEFAULT NULL,
  `started_at` bigint DEFAULT NULL,
  `payload_released_at` bigint DEFAULT NULL,
  `completed_at` bigint DEFAULT NULL,
  `archived_at` bigint DEFAULT NULL,
  `failed_reason` text,
  `deleted` tinyint(1) NOT NULL DEFAULT 0,
  `updated_by` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `create_time` bigint NOT NULL,
  `update_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UNI_MISSION_NO` (`mission_no`),
  KEY `idx_fire_event_id` (`fire_event_id`),
  KEY `idx_fc100_mission_incident` (`incident_id`),
  KEY `idx_workspace_status_ct` (`workspace_id`,`status`,`create_time`),
  KEY `idx_aircraft_sn` (`aircraft_sn`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='FC100 灭火任务';

-- ---------------------------------------------------------------------------
-- 3. fc100_mission_waypoint — 航点详情（P0-P6）
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `fc100_mission_waypoint`;
CREATE TABLE `fc100_mission_waypoint` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `mission_id` bigint unsigned NOT NULL,
  `waypoint_version` int NOT NULL DEFAULT 1,
  `waypoint_index` int NOT NULL COMMENT '0=P0 起飞 … 6=P6 返航',
  `waypoint_type` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `lat` double NOT NULL,
  `lng` double NOT NULL,
  `alt` double NOT NULL,
  `altitude_reference` varchar(16) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'ELLIPSOID',
  `speed` double DEFAULT NULL,
  `action` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `remark` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `create_time` bigint NOT NULL,
  `update_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_mission_version` (`mission_id`,`waypoint_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='FC100 灭火任务航点';

-- ---------------------------------------------------------------------------
-- 4. fc100_route_file — 航线文件历史（KML/KMZ 多版本）
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `fc100_route_file`;
CREATE TABLE `fc100_route_file` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `mission_id` bigint unsigned NOT NULL,
  `file_type` varchar(8) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT 'KML / KMZ',
  `schema_version` varchar(16) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'WPML_1.0.2',
  `file_name` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `object_key` varchar(1000) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT 'MinIO key',
  `sign` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT 'sha256',
  `size` bigint NOT NULL,
  `generator_version` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `is_latest` tinyint(1) NOT NULL DEFAULT 1,
  `created_by` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `create_time` bigint NOT NULL,
  `update_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_mission_latest` (`mission_id`,`is_latest`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='FC100 航线文件历史';

-- ---------------------------------------------------------------------------
-- 5. fc100_delivery_sync_log — Delivery Sync 调用日志
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `fc100_delivery_sync_log`;
CREATE TABLE `fc100_delivery_sync_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `mission_id` bigint unsigned DEFAULT NULL,
  `api_name` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `request_method` varchar(16) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `request_url` text,
  `request_body` longtext COMMENT 'H-4 LONGTEXT 支持大 KMZ body',
  `response_code` int DEFAULT NULL,
  `response_body` longtext,
  `success` tinyint(1) NOT NULL,
  `error_message` text,
  `ak_fingerprint` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT 'B-5 前6+后4，不存完整 AK',
  `idempotency_key` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `latency_ms` int DEFAULT NULL,
  `create_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_mission_id` (`mission_id`),
  KEY `idx_api_ct` (`api_name`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='Delivery Sync 调用日志';

-- ---------------------------------------------------------------------------
-- 6. fc100_mission_log — 任务状态变更审计
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `fc100_mission_log`;
CREATE TABLE `fc100_mission_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `mission_id` bigint unsigned NOT NULL,
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
  KEY `idx_mission_ct` (`mission_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='任务状态变更日志';

-- ---------------------------------------------------------------------------
-- 7. fc100_payload_event — 投放事件
-- ---------------------------------------------------------------------------
-- operation incident orchestration tables.
DROP TABLE IF EXISTS `operation_incident`;
CREATE TABLE `operation_incident` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `incident_no` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `fire_event_id` bigint unsigned NOT NULL,
  `level` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `status` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT 'CANDIDATE / CONFIRMED / DISPATCHING / RESPONDING / RECHECKING / RESOLVED / FALSE_ALARM / ABORTED / ARCHIVED',
  `center_lat` double DEFAULT NULL,
  `center_lng` double DEFAULT NULL,
  `risk_radius_m` double DEFAULT NULL,
  `created_by` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `confirmed_by` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `recommended_recheck` tinyint NOT NULL DEFAULT '0' COMMENT 'whether recheck/manual coordinate refinement is recommended',
  `recheck_reason` text COMMENT 'recheck recommendation or latest recheck result reason',
  `closed_at` bigint DEFAULT NULL,
  `create_time` bigint NOT NULL,
  `update_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UNI_OPERATION_INCIDENT_NO` (`incident_no`),
  KEY `idx_operation_incident_fire_event` (`fire_event_id`),
  KEY `idx_operation_incident_status_ct` (`status`,`create_time`),
  KEY `idx_operation_incident_level_ct` (`level`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='operation incident orchestration root';

DROP TABLE IF EXISTS `operation_assignment`;
CREATE TABLE `operation_assignment` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `incident_id` bigint unsigned NOT NULL,
  `resource_sn` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `role` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `status` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `lease_id` bigint unsigned DEFAULT NULL,
  `assigned_at` bigint NOT NULL,
  `released_at` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_operation_assignment_incident` (`incident_id`,`assigned_at`),
  KEY `idx_operation_assignment_role_status` (`incident_id`,`role`,`status`),
  KEY `idx_operation_assignment_resource` (`resource_sn`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='operation incident resource/personnel assignment';

DROP TABLE IF EXISTS `operation_resource_lease`;
CREATE TABLE `operation_resource_lease` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `resource_sn` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `lease_type` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `owner_type` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `owner_id` bigint unsigned NOT NULL,
  `expires_at` bigint DEFAULT NULL,
  `heartbeat_at` bigint DEFAULT NULL,
  `status` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `active_resource_sn` varchar(64) GENERATED ALWAYS AS (case when `status` = 'ACTIVE' then `resource_sn` else NULL end) STORED,
  `active_delivery_owner_id` bigint unsigned GENERATED ALWAYS AS (case when `status` = 'ACTIVE' and `lease_type` = 'DELIVERY_PRIMARY' then `owner_id` else NULL end) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UNI_OPERATION_LEASE_ACTIVE_RESOURCE` (`active_resource_sn`),
  UNIQUE KEY `UNI_OPERATION_LEASE_ACTIVE_DELIVERY_OWNER` (`active_delivery_owner_id`),
  KEY `idx_operation_lease_resource` (`resource_sn`,`status`),
  KEY `idx_operation_lease_owner` (`owner_type`,`owner_id`),
  KEY `idx_operation_lease_expire` (`status`,`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='operation resource lease placeholder for S4';

DROP TABLE IF EXISTS `operation_command_event`;
CREATE TABLE `operation_command_event` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `command_id` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `target_sn` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `command_type` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `mission_no` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `payload_json` longtext,
  `status` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `idempotency_key` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `operator_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `retry_count` int NOT NULL DEFAULT 0,
  `sent_at` bigint DEFAULT NULL,
  `ack_at` bigint DEFAULT NULL,
  `next_attempt_at` bigint DEFAULT NULL,
  `error_message` text,
  `create_time` bigint NOT NULL,
  `update_time` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UNI_OPERATION_COMMAND_ID` (`command_id`),
  UNIQUE KEY `UNI_OPERATION_COMMAND_IDEMPOTENCY` (`idempotency_key`),
  KEY `idx_operation_command_target` (`target_sn`,`status`,`create_time`),
  KEY `idx_operation_command_mission` (`mission_no`,`create_time`),
  KEY `idx_operation_command_due` (`status`,`next_attempt_at`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='operation command event queue placeholder for S4';

DROP TABLE IF EXISTS `operation_compliance_record`;
CREATE TABLE `operation_compliance_record` (
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

DROP TABLE IF EXISTS `operation_flight_application_record`;
CREATE TABLE `operation_flight_application_record` (
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

DROP TABLE IF EXISTS `operation_takeoff_confirmation_record`;
CREATE TABLE `operation_takeoff_confirmation_record` (
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

DROP TABLE IF EXISTS `operation_landing_report_record`;
CREATE TABLE `operation_landing_report_record` (
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

DROP TABLE IF EXISTS `operation_qualification_record`;
CREATE TABLE `operation_qualification_record` (
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

DROP TABLE IF EXISTS `operation_incident_log`;
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

-- fc100_payload_event
DROP TABLE IF EXISTS `fc100_payload_event`;
CREATE TABLE `fc100_payload_event` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `mission_id` bigint unsigned NOT NULL,
  `payload_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `event_type` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT 'RELEASE_PENDING / RELEASED / RELEASE_FAILED / FAULT',
  `event_value` text,
  `pre_release_checklist` json COMMENT 'M-3 各项勾选时间戳',
  `operator_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `create_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_mission_ct` (`mission_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='投放事件';

-- ---------------------------------------------------------------------------
-- 8. fc100_fire_review — M4T 复查结果
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `fc100_fire_review`;
CREATE TABLE `fc100_fire_review` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `mission_id` bigint unsigned NOT NULL,
  `before_temperature` double DEFAULT NULL,
  `after_temperature` double DEFAULT NULL,
  `temperature_unit` varchar(8) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'K',
  `after_thermal_image_url` text,
  `after_visible_image_url` text,
  `fire_suppressed` tinyint(1) DEFAULT NULL,
  `need_second_drop` tinyint(1) DEFAULT NULL,
  `suggestion` text,
  `reviewer_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
  `remark` text,
  `deleted` tinyint(1) NOT NULL DEFAULT 0,
  `create_time` bigint NOT NULL,
  `update_time` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UNI_MISSION_ID` (`mission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='M4T 复查';

-- 迁移完成。验证：SHOW TABLES LIKE 'fc100_%' 应返回 6 行；SHOW TABLES LIKE 'fire_%' 应返回 1 行。
