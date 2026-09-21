-- V4__create_assessments.sql
-- Append-only posture history. Every posture check ever run produces a
-- new `assessment` row (never updated, never deleted except via
-- ON DELETE CASCADE if an endpoint itself is removed) plus one
-- `check_result` row per individual check within it (firewall, ports,
-- applications, etc.). This is the evidence layer the whole platform's
-- OBSERVATION -> EVIDENCE -> HUMAN REVIEW model depends on - see
-- VE_Compliance_Engine_Full_Documentation.md Section 11.
--
-- Unlike the Python project's schema (MAC-string logical relationships
-- only), both tables here use real foreign keys - Postgres now catches
-- an orphaned/typo'd reference instead of silently allowing it.

CREATE TABLE IF NOT EXISTS assessment (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    endpoint_id     UUID NOT NULL REFERENCES endpoint(id) ON DELETE CASCADE,
    job_id          UUID REFERENCES posture_job(id) ON DELETE SET NULL,

    status          TEXT NOT NULL,      -- COMPLIANT, NON_COMPLIANT, ERROR
    detail          TEXT,               -- short human-readable summary

    started_at      TIMESTAMPTZ NOT NULL,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Every "what's the latest status of endpoint X" / "trend over the last
-- 7 days" query filters/sorts on these two columns.
CREATE INDEX IF NOT EXISTS idx_assessment_endpoint_created
    ON assessment(endpoint_id, created_at DESC);

CREATE TABLE IF NOT EXISTS check_result (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assessment_id   UUID NOT NULL REFERENCES assessment(id) ON DELETE CASCADE,

    check_type      TEXT NOT NULL,      -- FIREWALL, OPEN_PORTS, APPLICATIONS, ...
    status          TEXT NOT NULL,      -- COMPLIANT, NON_COMPLIANT, ERROR

    -- Flexible raw detail per check (e.g. which firewall profiles were
    -- disabled, which ports were open/blocked, which apps were found).
    -- JSONB rather than typed columns for now - matches what
    -- posture_agent.ps1 already emits, and avoids a schema migration
    -- every time a check's shape changes slightly. Promote a specific
    -- field to its own indexed column later only once a real query
    -- needs to filter/sort on it directly.
    details         JSONB,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_check_result_assessment
    ON check_result(assessment_id);

-- Lets Postgres query INTO the JSONB later (e.g. "find all assessments
-- where a specific port was open") without a schema change.
CREATE INDEX IF NOT EXISTS idx_check_result_details_gin
    ON check_result USING GIN (details);