-- V24__create_ise_sharing_rule.sql
-- X1 (DECISIONS D3, option B): a standing sharing rule approved by an ADMIN.
-- Versioned like app_policy: a change inserts version N+1 and deactivates the
-- previous one, so every feed fetch can be tied to the rule that allowed it.
-- Version 1 is seeded with scope NONE: the feed is empty until an admin opts in.
CREATE TABLE IF NOT EXISTS ise_sharing_rule (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version     INTEGER NOT NULL,
    scope       TEXT NOT NULL CHECK (scope IN ('NONE','CONNECTED','CONNECTED_NON_COMPLIANT')),
    active      BOOLEAN NOT NULL DEFAULT FALSE,
    created_by  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_ise_sharing_rule_version       ON ise_sharing_rule(version);
CREATE UNIQUE INDEX IF NOT EXISTS uq_ise_sharing_rule_single_active ON ise_sharing_rule(active) WHERE active;

INSERT INTO ise_sharing_rule (version, scope, active, created_by)
VALUES (1, 'NONE', TRUE, 'system')
ON CONFLICT (version) DO NOTHING;

-- One row per feed fetch: who pulled it, under which rule, how many endpoints.
-- Counts only, never the payload.
CREATE TABLE IF NOT EXISTS ise_feed_audit (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    caller          TEXT,
    rule_version    INTEGER NOT NULL,
    endpoint_count  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ise_feed_audit_occurred ON ise_feed_audit(occurred_at DESC);