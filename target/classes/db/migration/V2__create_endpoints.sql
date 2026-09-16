-- V2__create_endpoints.sql
CREATE TABLE IF NOT EXISTS endpoint (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mac_address         TEXT NOT NULL UNIQUE,   -- real business key, uppercased on write
    ip_address          TEXT,
    hostname            TEXT,
    os_name             TEXT,
    os_version          TEXT,

    connected           BOOLEAN NOT NULL DEFAULT FALSE,
    session_started_at  TIMESTAMPTZ,
    last_disconnected_at TIMESTAMPTZ,

    manufacturer        TEXT,
    model                TEXT,
    serial_number        TEXT,

    first_seen_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_endpoint_connected ON endpoint(connected);
CREATE INDEX IF NOT EXISTS idx_endpoint_last_seen ON endpoint(last_seen_at);