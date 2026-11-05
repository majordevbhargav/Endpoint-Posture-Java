-- V27__create_job_recovery_event.sql
-- PR5.3: Persistent audit log for job crash-recovery and expired lease reclamation.

CREATE TABLE IF NOT EXISTS job_recovery_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL REFERENCES posture_job(id) ON DELETE CASCADE,
    endpoint_id UUID NOT NULL REFERENCES endpoint(id) ON DELETE CASCADE,
    job_type TEXT NOT NULL,
    attempt_count INT NOT NULL,
    claimed_by TEXT,
    recovery_type TEXT NOT NULL CHECK (recovery_type IN ('PROCESS_TIMEOUT', 'LEASE_EXPIRED', 'WORKER_REVOKED')),
    reason TEXT NOT NULL,
    recovered_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_job_recovery_event_job_attempt
    ON job_recovery_event(job_id, attempt_count);

CREATE INDEX IF NOT EXISTS idx_job_recovery_event_endpoint
    ON job_recovery_event(endpoint_id, recovered_at DESC);

CREATE INDEX IF NOT EXISTS idx_job_recovery_event_recovered_at
    ON job_recovery_event(recovered_at DESC);

