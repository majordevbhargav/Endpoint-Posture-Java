-- V25__add_worker_lease_fields.sql
-- PR3 stage 2: lease ownership columns for remote workers.
--   claimed_by       identity of the worker that currently owns the job
--   lease_expires_at when that ownership lapses and the job may be reclaimed
-- Both are nullable: existing rows and local (in-process) jobs carry no lease.
-- attempt_count (V3) remains the fencing token; no extra counter is added.
-- Claim, completion and recovery behaviour is unchanged by this migration.
ALTER TABLE posture_job ADD COLUMN IF NOT EXISTS claimed_by TEXT;
ALTER TABLE posture_job ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMPTZ;
