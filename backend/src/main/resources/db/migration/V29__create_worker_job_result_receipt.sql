-- Durable, idempotency-keyed receipt for a worker's logical result envelope.
-- job_id and worker_id are historical identifiers, deliberately not foreign keys:
-- V28 establishes that audit history must survive deletion of referenced jobs/endpoints.
CREATE TABLE worker_job_result_receipt (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    worker_id UUID NOT NULL,
    result_hash CHAR(64) NOT NULL,
    schema_version INTEGER NOT NULL,
    collection_outcome TEXT NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_worker_job_result_receipt_job_attempt UNIQUE (job_id, attempt),
    CONSTRAINT ck_worker_job_result_receipt_attempt_positive CHECK (attempt > 0),
    CONSTRAINT ck_worker_job_result_receipt_schema_version_positive CHECK (schema_version > 0),
    CONSTRAINT ck_worker_job_result_receipt_outcome
        CHECK (collection_outcome IN ('SUCCESS', 'PARTIAL', 'FAILED')),
    CONSTRAINT ck_worker_job_result_receipt_hash
        CHECK (result_hash ~ '^[0-9a-f]{64}$')
);

-- Supports worker-scoped receipt/audit history without scanning the full receipt table.
CREATE INDEX idx_worker_job_result_receipt_worker_accepted
    ON worker_job_result_receipt(worker_id, accepted_at DESC);
