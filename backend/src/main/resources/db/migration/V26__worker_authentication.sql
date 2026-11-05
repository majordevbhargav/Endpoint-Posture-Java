-- V26: per-worker credentials, single-use enrollment tokens and audit events.
CREATE TABLE IF NOT EXISTS worker_registry (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    shard TEXT NOT NULL,
    key_hash TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revoked_by TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_worker_registry_key_hash ON worker_registry(key_hash);
CREATE UNIQUE INDEX IF NOT EXISTS uq_worker_registry_name ON worker_registry(lower(name));

CREATE TABLE IF NOT EXISTS worker_enrollment_token (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash TEXT NOT NULL,
    worker_name TEXT NOT NULL,
    shard TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    redeemed_at TIMESTAMPTZ,
    redeemed_worker_id UUID REFERENCES worker_registry(id) ON DELETE SET NULL,
    created_by TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_worker_enrollment_token_hash ON worker_enrollment_token(token_hash);

CREATE TABLE IF NOT EXISTS worker_auth_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    event_type TEXT NOT NULL CHECK (event_type IN
        ('TOKEN_CREATED','ENROLLED','ENROLL_REJECTED','AUTH_FAILED','AUTH_REVOKED','WORKER_REVOKED')),
    worker_id UUID,
    worker_name TEXT,
    detail TEXT,
    remote_addr TEXT
);
CREATE INDEX IF NOT EXISTS idx_worker_auth_event_occurred ON worker_auth_event(occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_posture_job_shard_claim
    ON posture_job(shard,priority DESC,created_at) WHERE status='QUEUED';
