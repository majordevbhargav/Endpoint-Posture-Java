# Running the backend on Linux (profile `linux`)

Start with `--spring.profiles.active=linux` (or `SPRING_PROFILES_ACTIVE=linux`).
In this mode the backend serves APIs, auth, database and scheduling only. The job
worker pool and recheck sweep are off, so nothing starts `powershell.exe`.
Windows dev (default profile) and `sim` are unchanged.

## Environment variables

| Variable | Required | Purpose |
|---|---|---|
| `DB_URL` | yes (deployment) | JDBC URL; default points at `localhost:5434` |
| `DB_USERNAME` | no (`ep_app`) | DB user |
| `DB_PASSWORD` | yes | DB password (no default) |
| `JWT_SECRET` | yes | JWT signing secret (no default) |
| `SEED_ADMIN_PASSWORD` | yes | initial admin password (no default) |
| `PORT` | no (`8090`) | HTTP port |
| `APP_SERVER_BASE_URL` | for workers | address Windows workers use to reach this backend |
| `POSTURE_SERVER_URL` | for workers | posture ingest URL handed to agents |

## Health probes

- `/actuator/health` overall (public)
- `/actuator/health/liveness` process is up
- `/actuator/health/readiness` includes the database check

## Known/intentional

- `LocalProcessAgentRunner` and `JobWorker` stay in the code but are only reachable via `JobWorkerPool`, which is disabled here.
- `SystemHealthService` reports DEGRADED while workers are disabled (Stage 3 will make this profile-aware).
- `StaleJobRecoveryScheduler` still runs; harmless with no local workers. Revisit with remote workers (PR3).
