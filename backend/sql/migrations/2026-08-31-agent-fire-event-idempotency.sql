-- Agent fire-event HTTP outbox uses fire_event.event_id as its idempotency key.
-- Fresh installations already create UNI_EVENT_ID in fc100_init.sql; this
-- migration safely brings upgraded installations to the same constraint.
SET @has_fire_event_id_unique_index = (
    SELECT COUNT(*)
    FROM (
        SELECT index_name
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'fire_event'
          AND non_unique = 0
        GROUP BY index_name
        HAVING COUNT(*) = 1
           AND MAX(column_name = 'event_id') = 1
    ) AS fire_event_unique_indexes
);

SET @ensure_fire_event_id_unique_sql = IF(
    @has_fire_event_id_unique_index = 0,
    'ALTER TABLE `fire_event` ADD UNIQUE KEY `UNI_EVENT_ID` (`event_id`)',
    'SELECT 1'
);

PREPARE ensure_fire_event_id_unique_stmt FROM @ensure_fire_event_id_unique_sql;
EXECUTE ensure_fire_event_id_unique_stmt;
DEALLOCATE PREPARE ensure_fire_event_id_unique_stmt;
