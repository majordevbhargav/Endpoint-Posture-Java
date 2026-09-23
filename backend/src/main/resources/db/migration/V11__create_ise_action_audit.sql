-- SQLBook: Code
-- V11__create_ise_action_audit.sql
-- Every Share Posture / Restrict / Clear Restriction action taken from
-- the dashboard, written unconditionally by IseActionService - success
-- or failure, always, per the project's audit rule (project plan
-- Section 13). Uses a real foreign key to endpoint(id), matching this
-- rewrite's convention over the Python prototype's MAC-string-only
-- relationship.

CREATE TABLE IF NOT EXISTS ise_action_audit (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    endpoint_id   UUID NOT NULL REFERENCES endpoint(id) ON DELETE CASCADE,
    action_type   TEXT NOT NULL CHECK (action_type IN ('SHARE_POSTURE','RESTRICT','CLEAR_RESTRICTION')),
    operator      TEXT,          -- the authenticated JWT subject; nullable only for pre-auth rows, if any ever exist
    succeeded     BOOLEAN NOT NULL,
    detail        TEXT,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ise_audit_endpoint_occurred
    ON ise_action_audit(endpoint_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_ise_audit_occurred
    ON ise_action_audit(occurred_at DESC);