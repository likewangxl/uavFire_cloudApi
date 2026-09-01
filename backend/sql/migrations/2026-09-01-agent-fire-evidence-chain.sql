-- Durable audit fields for Agent-side ONNX fire evidence.
USE `cloud_sample`;

ALTER TABLE `fire_event`
  ADD COLUMN `evidence_sha256` char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT 'agent original JPEG SHA-256' AFTER `visible_image_url`,
  ADD COLUMN `evidence_captured_at` bigint DEFAULT NULL COMMENT 'agent evidence capture time (epoch ms)' AFTER `evidence_sha256`,
  ADD COLUMN `model_sha256` char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT 'ONNX model SHA-256' AFTER `evidence_captured_at`;

ALTER TABLE `fire_event_history`
  ADD COLUMN `evidence_sha256` char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT 'agent original JPEG SHA-256' AFTER `visible_image_url`,
  ADD COLUMN `evidence_captured_at` bigint DEFAULT NULL COMMENT 'agent evidence capture time (epoch ms)' AFTER `evidence_sha256`,
  ADD COLUMN `model_sha256` char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT 'ONNX model SHA-256' AFTER `evidence_captured_at`;
