-- V1__create_users_and_roles.sql
-- Minimal security skeleton: one table, roles as an enum-backed column rather
-- than a join table for now. A join table (user_roles many-to-many) is the
-- obvious next step once there's more than one role in real use — not adding
-- it speculatively before that's true.

CREATE TABLE IF NOT EXISTS app_user (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username        TEXT NOT NULL UNIQUE,
    password_hash   TEXT NOT NULL,
    role            TEXT NOT NULL DEFAULT 'ADMIN',
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);