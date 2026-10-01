CREATE TABLE IF NOT EXISTS endpoint_security_indicator (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    endpoint_id   UUID NOT NULL REFERENCES endpoint(id) ON DELETE CASCADE,
    job_id        UUID REFERENCES posture_job(id) ON DELETE SET NULL,
    status        TEXT NOT NULL CHECK (status IN ('OK','WINRM_UNAVAILABLE','FAILED')),
    risk_level    TEXT CHECK (risk_level IN ('NONE','LOW','MEDIUM','HIGH')),
    findings      JSONB,
    summary       JSONB,
    raw_report    JSONB NOT NULL,
    error_message TEXT,
    collected_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_sec_ind_endpoint_collected
    ON endpoint_security_indicator(endpoint_id, collected_at DESC);