-- SQLBook: Code
-- V9__add_hardware_health_failure_tracking.sql
-- Lets a hardware-health job that failed before it could collect or score
-- anything still leave a permanent row, instead of the fact of the failure
-- living only in posture_job.error_message (overwritten on every retry,
-- gone once the job succeeds or is purged). Mirrors the same "a failed
-- attempt is still evidence" rule already applied to assessment/check_result
-- for posture checks.

ALTER TABLE hardware_health ALTER COLUMN cpu_score     DROP NOT NULL;
ALTER TABLE hardware_health ALTER COLUMN memory_score  DROP NOT NULL;
ALTER TABLE hardware_health ALTER COLUMN storage_score DROP NOT NULL;
ALTER TABLE hardware_health ALTER COLUMN overall_score DROP NOT NULL;
ALTER TABLE hardware_health ALTER COLUMN overall_band  DROP NOT NULL;

ALTER TABLE hardware_health ADD COLUMN IF NOT EXISTS succeeded     BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE hardware_health ADD COLUMN IF NOT EXISTS error_message TEXT;