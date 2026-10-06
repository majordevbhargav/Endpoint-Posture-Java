-- S8/S9: remember each endpoint's latest assessment, and keep a daily compliance rollup.
ALTER TABLE endpoint ADD COLUMN IF NOT EXISTS latest_assessment_id UUID;
ALTER TABLE endpoint ADD COLUMN IF NOT EXISTS latest_status        TEXT;
ALTER TABLE endpoint ADD COLUMN IF NOT EXISTS latest_assessed_at   TIMESTAMPTZ;

-- One-time backfill from existing history.
UPDATE endpoint e
   SET latest_assessment_id = a.id, latest_status = a.status, latest_assessed_at = a.created_at
  FROM (SELECT DISTINCT ON (endpoint_id) id, endpoint_id, status, created_at
          FROM assessment ORDER BY endpoint_id, created_at DESC) a
 WHERE a.endpoint_id = e.id;

-- Default list order (connected first, newest seen first) and the latest-assessment join.
CREATE INDEX IF NOT EXISTS idx_endpoint_connected_lastseen ON endpoint(connected, last_seen_at DESC);
CREATE INDEX IF NOT EXISTS idx_endpoint_latest_assessment  ON endpoint(latest_assessment_id);

-- One row per UTC day. The last snapshot of a day is its final value.
CREATE TABLE IF NOT EXISTS compliance_daily (
    day        DATE PRIMARY KEY,
    assessed   INTEGER NOT NULL,
    compliant  INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);