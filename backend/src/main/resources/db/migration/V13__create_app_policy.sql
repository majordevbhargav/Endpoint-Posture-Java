-- V13__create_app_policy.sql
-- Versioned application policy (required / blocked apps). One policy is
-- active at a time. Editing never updates a row in place: a new version is
-- inserted and the previous one is deactivated, so every past assessment
-- can be explained by the policy version stored in its APPLICATIONS check.
-- created_by / created_at on each version is the audit trail.

CREATE TABLE IF NOT EXISTS app_policy (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT NOT NULL,
    version     INTEGER NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT FALSE,
    created_by  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Version numbers are one lineage across all rows.
CREATE UNIQUE INDEX IF NOT EXISTS uq_app_policy_version ON app_policy(version);

-- The database itself refuses a second active policy.
CREATE UNIQUE INDEX IF NOT EXISTS uq_app_policy_single_active ON app_policy(active) WHERE active;

CREATE TABLE IF NOT EXISTS app_policy_rule (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id    UUID NOT NULL REFERENCES app_policy(id) ON DELETE CASCADE,
    app_pattern  TEXT NOT NULL,
    rule_type    TEXT NOT NULL CHECK (rule_type IN ('REQUIRED','BLOCKED'))
);

CREATE INDEX IF NOT EXISTS idx_app_policy_rule_policy ON app_policy_rule(policy_id);

-- Seed version 1 with the values that were previously hardcoded in the
-- agent, InventoryController and the frontend banner.
WITH p AS (
    INSERT INTO app_policy (name, version, active, created_by)
    VALUES ('Default application policy', 1, TRUE, 'system')
    RETURNING id
)
INSERT INTO app_policy_rule (policy_id, app_pattern, rule_type)
SELECT p.id, r.pattern, r.rule_type
FROM p
CROSS JOIN (VALUES
    ('Cisco Secure Client', 'REQUIRED'),
    ('uTorrent',            'BLOCKED'),
    ('TeamViewer',          'BLOCKED')
) AS r(pattern, rule_type);