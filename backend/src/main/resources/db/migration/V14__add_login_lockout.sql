-- V14__add_login_lockout.sql
-- Brute-force protection: consecutive failed logins and a temporary lock.
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS failed_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS locked_until    TIMESTAMPTZ;