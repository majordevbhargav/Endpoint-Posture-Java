-- V18__job_indexes.sql
-- S1: keep the job list and the nightly job retention cheap once posture_job is large.

-- "Newest N jobs" (GET /jobs, polled every 4 s by the Jobs page) sorts by created_at.
CREATE INDEX IF NOT EXISTS idx_posture_job_created
    ON posture_job(created_at DESC);

-- Nightly retention deletes finished jobs older than a cutoff by completed_at.
CREATE INDEX IF NOT EXISTS idx_posture_job_finished
    ON posture_job(completed_at)
    WHERE status IN ('COMPLETE', 'FAILED');