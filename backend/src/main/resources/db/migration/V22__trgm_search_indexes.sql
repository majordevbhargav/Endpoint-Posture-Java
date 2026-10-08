-- V22__trgm_search_indexes.sql
-- S12: trigram indexes for fast free-text search across endpoint identifiers and hardware model.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_endpoint_hostname_trgm
    ON endpoint USING gin (hostname gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_endpoint_mac_trgm
    ON endpoint USING gin (mac_address gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_endpoint_ip_trgm
    ON endpoint USING gin (ip_address gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_endpoint_os_trgm
    ON endpoint USING gin (os_name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_hardware_health_model_trgm
    ON hardware_health USING gin (model gin_trgm_ops);
