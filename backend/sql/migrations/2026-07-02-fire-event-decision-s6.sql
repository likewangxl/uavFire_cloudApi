USE `cloud_sample`;

ALTER TABLE `operation_incident`
  ADD COLUMN `recommended_recheck` tinyint NOT NULL DEFAULT '0'
    COMMENT 'whether recheck/manual coordinate refinement is recommended' AFTER `confirmed_by`,
  ADD COLUMN `recheck_reason` text
    COMMENT 'recheck recommendation or latest recheck result reason' AFTER `recommended_recheck`;
