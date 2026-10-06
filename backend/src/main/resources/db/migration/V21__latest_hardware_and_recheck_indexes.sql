ALTER TABLE endpoint ADD COLUMN IF NOT EXISTS latest_hw_id        UUID;
ALTER TABLE endpoint ADD COLUMN IF NOT EXISTS latest_hw_at        TIMESTAMPTZ;
ALTER TABLE endpoint ADD COLUMN IF NOT EXISTS latest_hw_succeeded BOOLEAN;
ALTER TABLE endpoint ADD COLUMN IF NOT EXISTS good_hw_id          UUID;
ALTER TABLE endpoint ADD COLUMN IF NOT EXISTS good_hw_at          TIMESTAMPTZ;
ALTER TABLE endpoint ADD COLUMN IF NOT EXISTS good_hw_band        TEXT;
ALTER TABLE endpoint ADD COLUMN IF NOT EXISTS good_hw_score       INTEGER;
ALTER TABLE endpoint ADD COLUMN IF NOT EXISTS good_hw_battery     INTEGER;

UPDATE endpoint e
   SET latest_hw_id = h.id, latest_hw_at = h.collected_at, latest_hw_succeeded = h.succeeded
  FROM (SELECT DISTINCT ON (endpoint_id) id, endpoint_id, collected_at, succeeded
          FROM hardware_health ORDER BY endpoint_id, collected_at DESC) h
 WHERE h.endpoint_id = e.id;

UPDATE endpoint e
   SET good_hw_id = h.id, good_hw_at = h.collected_at, good_hw_band = h.overall_band,
       good_hw_score = h.overall_score, good_hw_battery = h.battery_score
  FROM (SELECT DISTINCT ON (endpoint_id) id, endpoint_id, collected_at, overall_band, overall_score, battery_score
          FROM hardware_health WHERE succeeded ORDER BY endpoint_id, collected_at DESC) h
 WHERE h.endpoint_id = e.id;

CREATE INDEX IF NOT EXISTS idx_endpoint_good_hw_score ON endpoint(good_hw_score, id);
-- The recheck sweep probes by endpoint, type and newest job.
CREATE INDEX IF NOT EXISTS idx_posture_job_endpoint_type ON posture_job(endpoint_id, job_type, created_at DESC);