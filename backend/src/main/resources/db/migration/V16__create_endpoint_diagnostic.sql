-- V16__create_endpoint_diagnostic.sql
-- Endpoint 360: on-demand network diagnostics (gateway ping, DNS, TCP 443,
-- traceroute) run ON the endpoint. Append-only, one row per run. A run that
-- could not reach the endpoint over WinRM is still evidence (status
-- WINRM_UNAVAILABLE, score NULL, never 0).

CREATE TABLE IF NOT EXISTS endpoint_diagnostic (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    endpoint_id   UUID NOT NULL REFERENCES endpoint(id) ON DELETE CASCADE,
    job_id        UUID REFERENCES posture_job(id) ON DELETE SET NULL,

    status        TEXT NOT NULL CHECK (status IN ('OK','WINRM_UNAVAILABLE','FAILED')),
    score         INTEGER CHECK (score BETWEEN 0 AND 100),
    band          TEXT CHECK (band IN ('HEALTHY','WARNING','DEGRADED','CRITICAL')),
    deductions    JSONB,                 -- [{check, points, reason}] explaining the score
    raw_report    JSONB NOT NULL,        -- everything the agent measured
    error_message TEXT,

    collected_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_diagnostic_endpoint_collected
    ON endpoint_diagnostic(endpoint_id, collected_at DESC);