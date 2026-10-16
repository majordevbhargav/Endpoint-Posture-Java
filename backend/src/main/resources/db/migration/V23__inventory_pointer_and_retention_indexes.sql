-- V23__inventory_pointer_and_retention_indexes.sql
-- S13: each endpoint remembers its newest inventory row, so the paged
-- Installed Software / Listening Ports reads never scan inventory history.
ALTER TABLE endpoint ADD COLUMN IF NOT EXISTS latest_inventory_id UUID;
ALTER TABLE endpoint ADD COLUMN IF NOT EXISTS latest_inventory_at TIMESTAMPTZ;

UPDATE endpoint e
   SET latest_inventory_id = i.id, latest_inventory_at = i.collected_at
  FROM (SELECT DISTINCT ON (endpoint_id) id, endpoint_id, collected_at
          FROM endpoint_inventory ORDER BY endpoint_id, collected_at DESC) i
 WHERE i.endpoint_id = e.id;

CREATE INDEX IF NOT EXISTS idx_endpoint_latest_inventory
    ON endpoint(latest_inventory_id) WHERE latest_inventory_id IS NOT NULL;

-- S11 (90-day evidence retention): the nightly batched delete filters by time
-- and protects each endpoint's current pointers.
CREATE INDEX IF NOT EXISTS idx_assessment_created        ON assessment(created_at);
CREATE INDEX IF NOT EXISTS idx_hw_health_collected       ON hardware_health(collected_at);
CREATE INDEX IF NOT EXISTS idx_diagnostic_collected      ON endpoint_diagnostic(collected_at);
CREATE INDEX IF NOT EXISTS idx_sec_ind_collected         ON endpoint_security_indicator(collected_at);
CREATE INDEX IF NOT EXISTS idx_session_log_event_at      ON endpoint_session_log(event_at);
CREATE INDEX IF NOT EXISTS idx_inventory_collected       ON endpoint_inventory(collected_at);
CREATE INDEX IF NOT EXISTS idx_endpoint_latest_hw        ON endpoint(latest_hw_id) WHERE latest_hw_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_good_hw          ON endpoint(good_hw_id)   WHERE good_hw_id IS NOT NULL;

-- Foreign keys that are ON DELETE SET NULL need an index on the referencing
-- column, or every deleted parent row scans the child table. Without these,
-- job retention and evidence retention get slower as the tables grow.
CREATE INDEX IF NOT EXISTS idx_inventory_assessment ON endpoint_inventory(assessment_id) WHERE assessment_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_assessment_job       ON assessment(job_id)                WHERE job_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_hw_health_job        ON hardware_health(job_id)           WHERE job_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_diagnostic_job       ON endpoint_diagnostic(job_id)       WHERE job_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_sec_ind_job          ON endpoint_security_indicator(job_id) WHERE job_id IS NOT NULL;