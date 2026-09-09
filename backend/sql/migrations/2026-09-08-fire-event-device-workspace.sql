-- Repair only placeholder workspaces whose aircraft has one unambiguous bound workspace.
-- Run on the verified target schema after backing up; never use init.sql for this repair.
-- These persistent backup tables contain only affected rows. Rerunning is safe.
CREATE TABLE IF NOT EXISTS fire_event_bak_20260908_workspace LIKE fire_event;
CREATE TABLE IF NOT EXISTS fire_event_history_bak_20260908_workspace LIKE fire_event_history;
CREATE TABLE IF NOT EXISTS fire_mission_bak_20260908_workspace LIKE fc100_fire_mission;
DROP TEMPORARY TABLE IF EXISTS fire_event_workspace_repair;
CREATE TEMPORARY TABLE fire_event_workspace_repair (
  id BIGINT UNSIGNED PRIMARY KEY,
  old_workspace VARCHAR(64),
  target_workspace VARCHAR(64) NOT NULL
);

START TRANSACTION;
INSERT INTO fire_event_workspace_repair (id, old_workspace, target_workspace)
SELECT e.id, e.workspace_id, binding.workspace_id
FROM fire_event e
JOIN (
  SELECT d.device_sn, MIN(d.workspace_id) AS workspace_id
  FROM manage_device d
  JOIN manage_workspace w ON w.workspace_id = d.workspace_id
  WHERE d.bound_status = 1 AND d.workspace_id <> 'DEFAULT' AND TRIM(d.workspace_id) <> ''
  GROUP BY d.device_sn HAVING COUNT(DISTINCT d.workspace_id) = 1
) binding ON binding.device_sn = e.device_sn
WHERE e.deleted = 0 AND (e.workspace_id IS NULL OR TRIM(e.workspace_id) = '' OR e.workspace_id = 'DEFAULT');

INSERT IGNORE INTO fire_event_bak_20260908_workspace
SELECT e.* FROM fire_event e JOIN fire_event_workspace_repair r ON r.id = e.id;
INSERT IGNORE INTO fire_event_history_bak_20260908_workspace
SELECT h.* FROM fire_event_history h JOIN fire_event_workspace_repair r ON r.id = h.fire_event_id
WHERE h.workspace_id IS NULL OR TRIM(h.workspace_id) = '' OR h.workspace_id = 'DEFAULT';
INSERT IGNORE INTO fire_mission_bak_20260908_workspace
SELECT m.* FROM fc100_fire_mission m JOIN fire_event_workspace_repair r ON r.id = m.fire_event_id
WHERE m.workspace_id IS NULL OR TRIM(m.workspace_id) = '' OR m.workspace_id = 'DEFAULT';

UPDATE fire_event e JOIN fire_event_workspace_repair r ON r.id = e.id
SET e.workspace_id = r.target_workspace WHERE e.workspace_id <=> r.old_workspace;
SELECT ROW_COUNT() AS repaired_events;
UPDATE fire_event_history h JOIN fire_event_workspace_repair r ON r.id = h.fire_event_id
SET h.workspace_id = r.target_workspace
WHERE h.workspace_id IS NULL OR TRIM(h.workspace_id) = '' OR h.workspace_id = 'DEFAULT';
SELECT ROW_COUNT() AS repaired_history;
UPDATE fc100_fire_mission m JOIN fire_event_workspace_repair r ON r.id = m.fire_event_id
SET m.workspace_id = r.target_workspace
WHERE m.workspace_id IS NULL OR TRIM(m.workspace_id) = '' OR m.workspace_id = 'DEFAULT';
SELECT ROW_COUNT() AS repaired_missions;
COMMIT;
DROP TEMPORARY TABLE fire_event_workspace_repair;

-- Rollback in a transaction if needed: restore ONLY workspace_id by joining the
-- corresponding backup on id. Do not replace full rows or overwrite later reviews.
