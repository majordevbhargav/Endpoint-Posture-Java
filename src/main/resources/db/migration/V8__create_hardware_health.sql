-- SQLBook: Code
-- V8__create_hardware_health.sql
-- Endpoint Hardware Health module (mirrors V4's assessment/check_result
-- pattern: one hardware_health row per collection run, real FKs, JSONB
-- for the raw agent report so no schema change is needed as the
-- collector's own report shape evolves).

CREATE TABLE IF NOT EXISTS hardware_health (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    endpoint_id     UUID NOT NULL REFERENCES endpoint(id) ON DELETE CASCADE,
    job_id          UUID REFERENCES posture_job(id) ON DELETE SET NULL,

    manufacturer    TEXT,
    model           TEXT,
    serial_number   TEXT,
    bios_version    TEXT,

    cpu_score       INTEGER NOT NULL CHECK (cpu_score BETWEEN 0 AND 100),
    memory_score    INTEGER NOT NULL CHECK (memory_score BETWEEN 0 AND 100),
    storage_score   INTEGER NOT NULL CHECK (storage_score BETWEEN 0 AND 100),
    -- Nullable, not zero: a desktop with no battery is "not applicable",
    -- not "critically unhealthy" — a zero here would silently drag
    -- overall_score down for every desktop in the fleet.
    battery_score   INTEGER CHECK (battery_score BETWEEN 0 AND 100),

    overall_score   INTEGER NOT NULL CHECK (overall_score BETWEEN 0 AND 100),
    overall_band    TEXT NOT NULL CHECK (overall_band IN ('HEALTHY','WARNING','DEGRADED','CRITICAL')),

    hardware_event_count       INTEGER,
    warranty_status             TEXT,
    warranty_days_remaining     INTEGER,

    raw_report      JSONB NOT NULL,
    collected_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_hw_health_endpoint_collected
    ON hardware_health(endpoint_id, collected_at DESC);

CREATE INDEX IF NOT EXISTS idx_hw_health_report_gin
    ON hardware_health USING GIN (raw_report);

CREATE TABLE IF NOT EXISTS hardware_recommendation (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hardware_health_id  UUID NOT NULL REFERENCES hardware_health(id) ON DELETE CASCADE,
    priority            TEXT NOT NULL CHECK (priority IN ('LOW','MEDIUM','HIGH')),
    area                TEXT NOT NULL,
    action              TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_hw_reco_health_id
    ON hardware_recommendation(hardware_health_id);