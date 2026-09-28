-- SQLBook: Code
-- V12__create_endpoint_inventory.sql
-- Raw inventory (listening ports, installed apps, processes, resource usage)
-- from each posture run. Append-only, same rule as assessment.
CREATE TABLE IF NOT EXISTS endpoint_inventory (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    endpoint_id     UUID NOT NULL REFERENCES endpoint(id) ON DELETE CASCADE,
    assessment_id   UUID REFERENCES assessment(id) ON DELETE SET NULL,
    listening_ports JSONB,
    installed_apps  JSONB,
    top_processes   JSONB,
    resource_usage  JSONB,
    collected_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_inventory_endpoint_collected
    ON endpoint_inventory(endpoint_id, collected_at DESC);