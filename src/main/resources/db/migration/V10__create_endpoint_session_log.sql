-- SQLBook: Code
-- V10__create_endpoint_session_log.sql
-- Connect/disconnect history, independent of posture status (a device can
-- be connected with stale posture, or disconnected with a good historical
-- result). Populated by EndpointService.markConnected/markDisconnected,
-- called from IseSessionWatcher.

CREATE TABLE IF NOT EXISTS endpoint_session_log (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    endpoint_id  UUID NOT NULL REFERENCES endpoint(id) ON DELETE CASCADE,
    event_type   TEXT NOT NULL CHECK (event_type IN ('CONNECTED','DISCONNECTED')),
    ip_address   TEXT,
    event_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_session_log_endpoint_event
    ON endpoint_session_log(endpoint_id, event_at DESC);