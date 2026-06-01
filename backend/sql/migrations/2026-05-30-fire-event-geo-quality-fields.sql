USE `cloud_sample`;

ALTER TABLE `fire_event`
  ADD COLUMN `geo_method` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '火点定位方法, 如 RAY_DEM_RTK' AFTER `altitude_reference`,
  ADD COLUMN `geo_error_radius_m` double DEFAULT NULL COMMENT '火点定位误差半径(米)' AFTER `geo_method`,
  ADD COLUMN `geo_quality` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT 'AUTO_WAYPOINT_READY / DEM_MISSING / RTK_NOT_FIXED / LOW_ACCURACY' AFTER `geo_error_radius_m`,
  ADD COLUMN `geo_source_ts` bigint DEFAULT NULL COMMENT '定位快照源时间戳(ms epoch)' AFTER `geo_quality`,
  ADD COLUMN `aircraft_lat` double DEFAULT NULL COMMENT '检测时飞机纬度' AFTER `geo_source_ts`,
  ADD COLUMN `aircraft_lng` double DEFAULT NULL COMMENT '检测时飞机经度' AFTER `aircraft_lat`,
  ADD COLUMN `aircraft_alt` double DEFAULT NULL COMMENT '检测时飞机高程' AFTER `aircraft_lng`,
  ADD COLUMN `gimbal_pitch` double DEFAULT NULL COMMENT '检测时云台 pitch' AFTER `aircraft_alt`,
  ADD COLUMN `gimbal_yaw` double DEFAULT NULL COMMENT '检测时云台 yaw' AFTER `gimbal_pitch`,
  ADD COLUMN `gimbal_roll` double DEFAULT NULL COMMENT '检测时云台 roll' AFTER `gimbal_yaw`,
  ADD COLUMN `thermal_roi` text COMMENT '热区 ROI JSON' AFTER `gimbal_roll`;

ALTER TABLE `fire_event_history`
  ADD COLUMN `geo_method` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '火点定位方法, 如 RAY_DEM_RTK' AFTER `altitude_reference`,
  ADD COLUMN `geo_error_radius_m` double DEFAULT NULL COMMENT '火点定位误差半径(米)' AFTER `geo_method`,
  ADD COLUMN `geo_quality` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT 'AUTO_WAYPOINT_READY / DEM_MISSING / RTK_NOT_FIXED / LOW_ACCURACY' AFTER `geo_error_radius_m`,
  ADD COLUMN `geo_source_ts` bigint DEFAULT NULL COMMENT '定位快照源时间戳(ms epoch)' AFTER `geo_quality`,
  ADD COLUMN `aircraft_lat` double DEFAULT NULL COMMENT '检测时飞机纬度' AFTER `geo_source_ts`,
  ADD COLUMN `aircraft_lng` double DEFAULT NULL COMMENT '检测时飞机经度' AFTER `aircraft_lat`,
  ADD COLUMN `aircraft_alt` double DEFAULT NULL COMMENT '检测时飞机高程' AFTER `aircraft_lng`,
  ADD COLUMN `gimbal_pitch` double DEFAULT NULL COMMENT '检测时云台 pitch' AFTER `aircraft_alt`,
  ADD COLUMN `gimbal_yaw` double DEFAULT NULL COMMENT '检测时云台 yaw' AFTER `gimbal_pitch`,
  ADD COLUMN `gimbal_roll` double DEFAULT NULL COMMENT '检测时云台 roll' AFTER `gimbal_yaw`,
  ADD COLUMN `thermal_roi` text COMMENT '热区 ROI JSON' AFTER `gimbal_roll`;
