-- V3__create_posture_jobs.sql
-- Replaces the Python project's pending_devices.txt / seen_macs.txt flat
-- files with a real, concurrency-safe Postgres queue. Multiple worker
-- threads (or a restart racing an in-flight run) claiming from this table
-- must never double-run the same job - see PostureJobRepository's
-- findNextClaimable() for the FOR UPDATE SKIP LOCKED claim pattern that
-- guarantees this.

CREATE TABLE IF NOT EXISTS posture_job (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    endpoint_id     UUID NOT NULL REFERENCES endpoint(id) ON DELETE CASCADE,

    job_type        TEXT NOT NULL DEFAULT 'POSTURE_CHECK',
    status          TEXT NOT NULL DEFAULT 'QUEUED',   -- QUEUED, RUNNING, COMPLETE, FAILED

    priority        INTEGER NOT NULL DEFAULT 0,       -- higher claims first
    attempt_count   INTEGER NOT NULL DEFAULT 0,
    max_attempts    INTEGER NOT NULL DEFAULT 3,
    error_message   TEXT,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ                       -- null = eligible immediately
);

-- The claim query filters on exactly these two columns - this index is
-- what keeps claiming fast once the table has real volume in it.
CREATE INDEX IF NOT EXISTS idx_posture_job_claimable
    ON posture_job(status, next_attempt_at);

CREATE INDEX IF NOT EXISTS idx_posture_job_endpoint
    ON posture_job(endpoint_id);