-- V19__posture_job_shard.sql
-- S5: prep for a remote runner fleet (DECISIONS.md D2, option C). Every job belongs to a
-- shard; today everything is in 'default'. Claims do not filter by shard yet.
ALTER TABLE posture_job ADD COLUMN IF NOT EXISTS shard TEXT NOT NULL DEFAULT 'default';