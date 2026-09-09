-- Run against the configured application database before starting the updated backend.
-- Additive and rerunnable; no history is rewritten.
SET @cc_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fire_event_history' AND COLUMN_NAME = 'decision_reason');
SET @cc_sql = IF(@cc_exists = 0, 'ALTER TABLE fire_event_history ADD COLUMN decision_reason varchar(1000) DEFAULT NULL COMMENT ''人工复核说明''', 'SELECT 1');
PREPARE cc_stmt FROM @cc_sql;
EXECUTE cc_stmt;
DEALLOCATE PREPARE cc_stmt;
