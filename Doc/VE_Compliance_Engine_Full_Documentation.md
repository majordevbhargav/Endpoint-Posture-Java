# VE Compliance Engine — Full Project Documentation & Spring Boot Migration Guide

**Purpose of this document:** a single reference that (1) documents everything the current Python/Flask/PostgreSQL system actually does, file by file, and (2) lays out everything you need to think about, design, and build if you rebuild this as a Java/Spring Boot + PostgreSQL + React/Next.js system.

---

# PART 1 — THE CURRENT PYTHON PROJECT, DOCUMENTED

## 1. What the product is

An **agentless endpoint posture and compliance visibility platform** that integrates with Cisco ISE. It:

- Discovers endpoints connected to the network via Cisco ISE's Session Directory (MNT ActiveList API).
- Runs agentless posture checks against Windows endpoints over WinRM/CIM (no agent installed on the endpoint).
- Stores every result as permanent, append-only history in PostgreSQL.
- Shows results to a human operator on a web dashboard.
- Lets the operator **explicitly** decide to share posture with ISE or restrict/clear-restrict a device — nothing happens automatically.

**Core architectural principle (do not lose this in any rewrite):**

```
OBSERVATION → EVIDENCE → HUMAN REVIEW → OPTIONAL ISE ACTION
```

Cisco ISE remains the sole enforcement authority. The platform never issues a CoA or ANC action without an explicit admin click. This was a deliberate fix to the original prototype, which coupled detection directly to enforcement.

## 2. High-level architecture (as built)

```
Cisco ISE (Session Directory / MNT ActiveList / ERS REST)
        │
        ▼
ise_session_watcher.py  → pending_devices.txt (flat-file queue, Windows byte-locked)
        │
        ▼
posture_agent.ps1  (WinRM/CIM, DCOM→WSMan fallback, 30s per-op timeout)
        │  HTTP POST JSON
        ▼
posture_app.py  (Flask, ingestion only — NO auto ISE writes)
        │
        ▼
PostgreSQL  (posture_db.py — shim layer translating SQLite-style calls to psycopg2)
        │
        ▼
posture_ui.py  (Flask, dashboard API + queue worker + hardware-health worker)
        │
        ▼
dashboard.html / console.html  (plain HTML/CSS/JS + Chart.js, no framework)
        │
   ┌────┴────┐
   ▼         ▼
Share Posture   Restrict / Clear Restriction
   │                 │
   └────────┬────────┘
            ▼
   ise_transport.py → ers_transport.py → Cisco ISE ERS REST
```

## 3. Backend services (processes)

Three independent long-running Python processes, all on a Windows host (WinRM line-of-sight to endpoints required):

| Process | Port | Role |
|---|---|---|
| `posture_app.py` | 8000 | Receives posture JSON from `posture_agent.ps1`, persists it, exposes admin-triggered ISE action routes |
| `posture_ui.py` | 5000 | Dashboard API, serves `dashboard.html`/`console.html`, runs the queue auto-worker and hardware-health background worker, proxies ISE actions to `posture_app.py` |
| `ise_session_watcher.py` | — (no HTTP server) | Polls ISE's MNT ActiveList every `WATCHER_POLL_SECONDS` (default 20s), tracks connect/disconnect, queues devices due for a recheck |

All three load the same `.env` file and the same `posture_db.py` module.

## 4. File-by-file inventory

### `posture_db.py`
PostgreSQL data-access layer. Historically SQLite; rewritten to keep the **exact same public function names** (`save_assessment`, `get_assessments`, `db()`, `init_db()`, etc.) so every calling module needed zero changes.

Key internal mechanism — the SQLite→Postgres shim:
- `_Connection.execute(sql, params)` regex-translates `?` placeholders to `%s`.
- Returns `psycopg2.extras.RealDictCursor` rows, so `row["col"]` keeps working.
- `ThreadedConnectionPool` (default max 10 connections, `POSTGRES_MAX_CONN`).
- `.env` is loaded **inside this module** (not just in the three entrypoint scripts), fixing a real bug where a bare `python -c "from posture_db import ..."` silently used hardcoded defaults.

Schema owned here (Group A/B/C/D — see Database Reference below): `endpoints`, `assessments`, `check_results`, `needs_attention`, `endpoint_ports`, `endpoint_processes`, `endpoint_apps`, `endpoint_session_log`, `ise_action_audit`, `endpoint_hardware_health`, `endpoint_hardware_recommendations`.

Notable fixed bug: a `CREATE INDEX ... USING GIN (report_json)` originally ran **before** the migration that converts `report_json` from `TEXT` to `JSONB`, which Postgres rejects (no GIN operator class for text). Fixed by sequencing: `SCHEMA_SQL` → `_JSONB_MIGRATION_SQL` → `_JSONB_GIN_INDEX_SQL`, all three run every `init_db()` call, all idempotent.

### `posture_app.py`
Flask app, posture ingestion only.

- `POST /api/v1/posture` — validates, stores via `save_assessment()`, **does not** touch ISE. Optional `POSTURE_API_KEY` header check.
- `POST /api/v1/endpoints/<mac>/share-posture` — pulls latest assessment, calls `ise_transport.get_transport().publish_posture()`, logs to `ise_action_audit` regardless of outcome.
- `POST /api/v1/endpoints/<mac>/restrict` / `/clear-restriction` — same pattern via `publish_enforcement()`.
- `GET /api/v1/endpoints/<mac>/ise-status`, `GET /health`.

### `posture_ui.py`
Largest file. Flask app serving the dashboard and all its supporting APIs.

- Serves `dashboard.html` at `/` and `console.html` at `/console` (read from disk under `FRONTEND_DIR`, with an inline fallback `INDEX_HTML` if the file is missing).
- **Auto-worker thread**: drains `pending_devices.txt` (one item at a time, `AUTO_WORKER_POLL_SECONDS` default 3s) and runs `posture_agent.ps1` against it via `subprocess.run`.
- **Hardware-health worker thread**: sweeps endpoints with a confirmed-good posture assessment (proxy for "this is a reachable Windows box") whose hardware-health report is missing or older than `HW_HEALTH_RECHECK_INTERVAL_SECONDS` (default 24h), runs `hardware_health_agent.ps1` against each, with in-memory per-MAC failure backoff (`HW_HEALTH_FAILURE_BACKOFF_SECONDS`, default 6h) so unreachable non-Windows devices aren't retried every sweep.
- **ISE action passthrough**: `/api/v1/endpoints/<mac>/share-posture|restrict|clear-restriction` forward to `posture_app.py` over HTTP so the dashboard never talks to ISE directly.
- **Dashboard/data APIs**: `/api/dashboard/summary`, `/trend`, `/categories`, `/endpoints`, `/health`; `/api/v1/applications`, `/api/v1/ports`, `/api/v1/endpoints`, `/api/v1/ise/endpoints`; `/api/endpoint-360/*` (fleet diagnostics: ping, DNS, TCP-443 probe, `tracert`, history); `/api/audit/ise-actions`; `/api/needs_attention`, `/api/check`, `/api/skip`.
- Registers three sub-blueprints on startup: `register_endpoint_360(app)`, `register_remediation(app)`, `register_hardware_health(app)`.
- Fixed bug: `needs_attention` is keyed by **IP only**, not MAC — three `mac in needs` checks in route handlers were dead code (`needs_attention` never contains MACs) and were removed rather than left as misleading no-ops.
- Fixed bug: dashboard summary query now filters live compliance counts to `connected = 1` only, adding a separate `not_connected` count (Project Plan Problem 2 / Section 8.4).

### `ise_session_watcher.py`
No HTTP server — a polling loop.

- Polls `{ISE_HOST}/admin/API/mnt/Session/ActiveList` every `WATCHER_POLL_SECONDS` (default 20s).
- Parses the XML response into `{MAC: fields}`.
- For each currently-active MAC: `mark_connected(mac, ip)`, writes `ip_mac_map.txt`, and enqueues to `pending_devices.txt` if `due_for_check()` (never checked, or `RECHECK_INTERVAL_SECONDS`, default 4h, has elapsed since last queue time — tracked in `seen_macs.txt` as `MAC,epoch`).
- For MACs that dropped out of the active list since the last poll: `mark_disconnected(mac)` **and removes them from `seen`**, so a reconnect is immediately due for a recheck instead of waiting out the rest of the 4h window. This is the fix for "Problem 2" in the project plan (status never expiring).
- Uses `msvcrt.locking` (Windows-only) for safe concurrent access to the flat queue file.

### `posture_agent.ps1`
The actual Windows posture collector, run per-device (locally or remote via WinRM/CIM).

Collects: OS info, primary IP/MAC, hardware identity (`Win32_ComputerSystem`, `Win32_BIOS`), CPU/memory utilization, top 10 processes by memory, Windows Firewall profile state, listening TCP ports (`MSFT_NetTCPConnection`) **with active reachability probing** (raw `TcpClient` connect with a short configurable timeout — not `Test-NetConnection`, which has no configurable timeout and can hang 10–20s per port), and installed applications (registry via `Get-ItemProperty` locally, or `StdRegProv`-over-CIM with a **WinRM/`Invoke-Command` fallback** remotely, since `StdRegProv` depends on the "Remote Registry" service which is disabled by default on client Windows editions).

Remote connection pattern: DCOM first, WSMan fallback, **explicit 30s `-OperationTimeoutSec`** on both (fixed bug: without this, a target with a hung-but-listening WinRM service could ride out WinRM's own 180s default, racing against and often losing to the outer Python subprocess timeout — which killed the process before any real error text could be written out, producing an opaque "timed out" message even on previously-healthy devices).

Submits JSON to `posture_app.py`'s `/api/v1/posture`, and always emits a `RESULT_JSON:` line to stdout so `posture_ui.py` can parse a machine-readable result even when the direct HTTP submit fails.

### `hardware_health_agent.ps1`
Sibling agent for Phase 3a (Hardware Health). Same WinRM/CIM DCOM→WSMan pattern, same shared credential loading logic as `posture_agent.ps1`. Collects identity, CPU/memory, `Get-PhysicalDisk` storage health, `BatteryStaticData` (design vs full-charge capacity), and a 7-day hardware-relevant Windows Event Log window (filtered to `WHEA|disk|storport|stornvme|Ntfs|Kernel-Power|Display|USB`). Submits to `/api/v1/hardware-health`. Fixed bug: default `-PostureAppBase` pointed at port 8000 (`posture_app.py`), but that route is actually registered on `posture_ui.py` (port 5000) — every default-argument submission 404'd. Corrected.

### `endpoint_hardware_health_integration.py`
Flask blueprint (`register_hardware_health(app)`). Owns `endpoint_hardware_health` + `endpoint_hardware_recommendations` tables. Scores CPU/memory/storage/battery 0–100 from the raw report (illustrative bands: 85+ HEALTHY, 70–84 WARNING, 50–69 DEGRADED, <50 CRITICAL — **explicitly unconfirmed**, pending project-plan Section 15 Q10). Stores the full raw report as JSONB (`psycopg2.extras.Json()`, not `json.dumps()` — so reads come back as native dicts). Routes: submit, get-latest, get-history, fleet-list.

### `application_remediation.py`
Flask blueprint (`register_remediation(app)`). Classifies discovered applications (Business Relevant / Irrelevant / Review), tracks a select-for-uninstall set, and performs **remote uninstall over PowerShell Remoting** (`Invoke-Command`) using either explicit credentials, the shared DPAPI-encrypted common credential (`posture_common_cred.xml`), or none. A `PROTECTED_KEYWORDS` list (Windows, drivers, security agents) blocks automatic removal of critical software. Every action is written to `remediation_audit`.

Fixed bug: `_remove_inventory_app`'s "does this app still exist elsewhere in the fleet" check used SQL `TRIM()` only (leading/trailing whitespace) while the module's own `_norm()` (used for the `app_key` primary key everywhere else) also collapses **internal** whitespace runs. An app name with irregular internal spacing could pass one check and fail the other, silently leaving stale `app_uninstall_selection` rows. Fixed by normalizing identically in Python on both sides.

WinRM timeouts here are deliberately **shorter than the outer subprocess timeout** (`WINRM_OPEN_TIMEOUT_MS`=20s, `WINRM_OPERATION_TIMEOUT_MS`=150s, `SUBPROCESS_TIMEOUT_SECONDS`=200s) for the same race-condition reason documented in `posture_agent.ps1`.

### `endpoint_360_integration.py`
Flask blueprint (`register_endpoint_360(app)`). Runs two **background collector threads** against the console's own machine (not the remote fleet):
- Experience collector (`endpoint_experience_dashboard.py`): Wi-Fi, gateway ping, DNS, internet ping, TCP-443 + HTTPS application probe, traceroute, rolled into a 0–100 experience score with a deducted-points root-cause model.
- Security collector (`endpoint_security_indicators.py`): active TCP connections sampled over a window, detecting possible lateral movement (fan-out on SMB/RPC/RDP/WinRM/SSH ports), possible C2 beaconing (periodicity analysis across snapshots), and unusual external ports/volume.

Also exposes `/api/endpoint-360/diagnostic` (called from the dashboard against a *selected fleet endpoint*, not the console) which pings, resolves DNS, probes TCP-443, and runs `tracert` live, transparently scored via `_endpoint_360_health()` in `posture_ui.py`.

### `endpoint_experience_dashboard.py` / `endpoint_security_indicators.py`
Standalone collector scripts (importable modules), each independently runnable with their own CLI/dashboard for local debugging. See above for what they collect.

### `ise_transport.py` / `ers_transport.py` / `pxgrid_transport.py`
Transport abstraction — exactly two operations: `publish_posture()` and `publish_enforcement()`. `ers_transport.py` is the only real implementation (ISE ERS REST, HTTP Basic Auth, endpoint custom-attribute writes, ANC apply/clear or CoA reauth depending on `ENFORCEMENT_MODE`). `pxgrid_transport.py` is an intentional stub that raises immediately on construction — pxGrid was **removed from project scope** in project-plan v1.2, though the stub file is still present (a known doc/code mismatch, harmless since it's never selected by default).

### Frontend: `dashboard.html`, `console.html`
Plain HTML/CSS/vanilla JS, no build step, no framework. `dashboard.html` is a large single-page app (sidebar nav, hash-based routing, ~15 "pages" toggled via `display:none`/`active`) covering Dashboard, Assessments, Applications, App Remediation, Endpoint 360, Hardware Health, Endpoints, ISE Actions, Ports, System Health, plus several "coming soon" placeholders (Policies, Compliance, Reports, Settings). Uses Chart.js (via CDN) for the donut/trend/hardware-trend charts, with graceful degradation if the CDN is blocked. `console.html` is the older, simpler queue/results view, kept largely for the manual retry/skip/credential-override workflow.

### Database schema (see `Database_Reference.md` in project files for full column-level detail)
Groups:
- **A — Core posture**: `endpoints`, `assessments`, `check_results`, `needs_attention`, `endpoint_ports`, `endpoint_processes`, `endpoint_apps`
- **B — Connection tracking**: `endpoint_session_log`
- **C — ISE admin actions**: `ise_action_audit`
- **D — Hardware Health**: `endpoint_hardware_health`, `endpoint_hardware_recommendations`
- **E — Endpoint 360**: `endpoint_experience_history`, `endpoint_security_history`, `endpoint_360_diagnostics`
- **F — Application Remediation**: `app_classification`, `app_uninstall_selection`, `remediation_audit`

Only one real foreign key exists in the whole schema: `check_results.assessment_id → assessments.id ON DELETE CASCADE`. Every other MAC-based relationship is logical only, enforced in application code, not by Postgres.

## 5. Flat-file coordination (acknowledged prototype-scale mechanism)

`pending_devices.txt` (queue), `seen_macs.txt` (MAC → last-queued epoch), `ip_mac_map.txt` (IP↔MAC lookup) — all plain text, Windows byte-locked (`msvcrt`), shared between `ise_session_watcher.py`, `posture_ui.py`'s auto-worker, and `posture_agent.ps1`. Explicitly called out in the project plan as needing replacement (Redis, Phase 5+) once scale genuinely requires it — not before.

## 6. Bugs found and fixed (full list, for migration awareness)

| # | Bug | Fix |
|---|---|---|
| 1 | `INTEGER PRIMARY KEY AUTOINCREMENT` (SQLite-only) in 3 files | → `SERIAL PRIMARY KEY` |
| 2 | `INSERT OR IGNORE` | → `INSERT ... ON CONFLICT DO NOTHING` |
| 3 | `datetime('now', ?)` (SQLite-only) | → cutoff computed in Python, passed as ISO text |
| 4 | `.env` only loaded by 3 entrypoint scripts | → moved into `posture_db.py` itself |
| 5 | Remediation credential path pointed at wrong folder | → corrected to `backend/agents/` |
| 6 | `ORDER BY ... COLLATE NOCASE` (SQLite-only) | → `ORDER BY LOWER(...)` |
| 7 | Hardcoded "SQLite database active" message | → real Postgres connectivity check |
| 8 | GIN index created before TEXT→JSONB migration | → migration now runs first |
| 9 | `_remove_inventory_app` used `TRIM()` only vs `_norm()` elsewhere | → normalized identically in Python |
| 10 | `mac in needs_attention` checks (3 places) — `needs_attention` keyed by IP only | → dead code removed |
| 11 | `hardware_health_agent.ps1` defaulted to port 8000 instead of 5000 | → corrected default |
| 12 | CIM/WinRM operations had no explicit timeout, raced against outer subprocess timeout | → explicit shorter internal timeouts added everywhere |

## 7. Known unresolved/open items (do not silently "finish" these in a rewrite without confirming)

- Operator authentication / RBAC — dashboard currently has none; `ise_action_audit.operator` is always blank.
- Real ISE environment details (host, version, TLS verification, actual ANC policy name — code assumes `"Quarantine"`).
- Hardware-health score thresholds (85/70/50 bands) — illustrative only.
- Warranty data source (CSV vs OEM API) — currently always `UNKNOWN`.
- Scale target (pilot vs. the 40,000-endpoint / 20,000-session long-term architecture diagram) — determines how urgently the flat-file queue and SQLite-era assumptions need replacing.
- `endpoint_productivity_browsing_dashboard.py` (browser-history collection) — explicitly out of scope pending legal/HR sign-off; do not resurrect without that confirmation.
- pxGrid — out of scope per project-plan v1.2; the stub file should probably be deleted for consistency, but functionally inert.

---

# PART 2 — REBUILDING AS SPRING BOOT: EVERYTHING TO KEEP IN MIND

## 1. Target stack

```
Next.js + TypeScript (frontend)
        │ REST (JSON)
        ▼
Spring Boot (Java 21+) — modular monolith
        │
   ┌────┼────────────────┐
   ▼    ▼                ▼
PostgreSQL   Cisco ISE   Job/Queue (Postgres-backed initially)
                              │
                              ▼
                    PowerShell agents (keep as-is)
                              │
                              ▼
                        Windows endpoints
```

**Start as a modular monolith, not microservices.** Redis and Kafka are later-phase additions, introduced only when a specific, felt problem (real concurrent job volume, real event-stream fan-out) justifies them — not on day one "because enterprise."

## 2. What to keep unchanged

- **PowerShell agents** (`posture_agent.ps1`, `hardware_health_agent.ps1`, `Save-PostureCredential.ps1`). Rewriting Windows-native CIM/WinRM/WMI logic in Java buys nothing — keep them as the collection layer, have them POST JSON to the new Spring Boot ingestion endpoint instead of the Flask one. Same DCOM→WSMan fallback, same explicit operation timeouts, same credential model (DPAPI `Export-Clixml`, decryptable only by the same Windows account/session).
- **PostgreSQL** as the database — this is already the right choice; carry the schema forward (with proper foreign keys this time, and Flyway-managed migrations instead of hand-rolled idempotent `CREATE TABLE IF NOT EXISTS` blocks).
- **The core enforcement-decoupling principle.** Whatever you build, a posture ingestion endpoint must never call an ISE enforcement method directly. Keep "share posture" and "restrict/clear" as separate, explicit, audited operations.
- **The ISE transport abstraction** (`publish_posture` / `publish_enforcement` / `reachable`) — this maps directly onto a Java interface with a Spring bean implementation, and is worth preserving as-is.

## 3. Proposed Spring Boot package structure

```
com.<yourorg>.endpointposture
├── endpoint/       controller, service, repository, entity, dto
├── posture/        controller, service, repository, entity, dto
├── hardware/       controller, service, repository, entity, dto
├── ise/            controller, service, transport (interface + ERS impl), dto
├── session/        connection-state tracking (mark_connected/disconnected equivalent)
├── remediation/    application classification + remote uninstall orchestration
├── endpoint360/    experience + security indicator ingestion/history
├── audit/          ise_action_audit + remediation_audit equivalents
├── scheduler/      replaces ise_session_watcher.py + auto_worker + hardware_health_worker
├── security/       auth, RBAC, JWT/OAuth2
└── job/            Postgres-backed job/queue table replacing pending_devices.txt
```

## 4. Database migration plan

- Use **Flyway** (or Liquibase) from day one — a real migration history, not idempotent `CREATE TABLE IF NOT EXISTS` scattered across app startup code.
- Port the existing SQL files (`001_init_core.sql` through `005_report_json_jsonb.sql`) as your first Flyway migrations, then add real foreign keys where the Python version only had logical (MAC-string) relationships — e.g. `assessments.mac`, `endpoint_apps.mac`, `endpoint_hardware_health.mac` should all `REFERENCES endpoints(mac)`, catching typo'd MACs Postgres currently lets through silently.
- Keep JSONB for the free-form collector reports (`report_json` columns) — this was a deliberate, good decision (Postgres can index/query into it later without a schema change per new collector shape).
- Replace `pending_devices.txt` / `seen_macs.txt` / `ip_mac_map.txt` with real tables from the start (a `posture_jobs` table with status/priority/retry columns, an `ip_mac_map` table, and connection-state tracking already living in `endpoints`/`endpoint_session_log`). This removes the Windows-byte-lock flat-file coordination entirely.

## 5. API design (REST resource shape)

```
/api/v1/endpoints
/api/v1/endpoints/{id}
/api/v1/endpoints/{id}/posture
/api/v1/endpoints/{ihardd}/ware
/api/v1/endpoints/{id}/ports
/api/v1/endpoints/{id}/applications
/api/v1/endpoints/{id}/processes
/api/v1/endpoints/{id}/sessions

/api/v1/ise/sessions
/api/v1/ise/posture/share
/api/v1/ise/enforcement/restrict
/api/v1/ise/enforcement/clear

/api/v1/jobs
/api/v1/jobs/{id}

/api/v1/audit
/api/v1/audit/ise-actions
```

Keep the ingestion endpoint (`POST /api/v1/posture` equivalent) and the three admin-triggered ISE-action endpoints as **structurally separate** — the Python version's Section 8.1/8.2 split is the whole point of the platform and must survive the rewrite unchanged.

## 6. Security model to design (currently absent in Python version)

The Python dashboard has **no authentication at all** — this is explicitly flagged as an open question (Section 15, Q6/Q8 in the project plan). A Spring Boot rewrite is the natural point to add:

- Spring Security + JWT/OAuth2 (or SSO against an existing IdP, per the original open question).
- Roles worth planning for: Admin (manage endpoints/ISE config/users), Security Analyst (view + share posture), Network Operator (restrict/clear), Viewer (read-only).
- Once real auth exists, `ise_action_audit.operator` (and its remediation-audit equivalent) should be populated from the authenticated principal, not left blank.

## 7. Job/scheduling design

Replace three separate Python mechanisms with one coherent model:
- `ise_session_watcher.py`'s polling loop → a Spring `@Scheduled` task hitting ISE's MNT ActiveList.
- `posture_ui.py`'s auto-worker (queue drain) → a Postgres-backed `posture_jobs` table + a worker (Spring `@Scheduled` polling, or `@Async` executor pool) consuming it.
- `hardware_health_worker` (24h recheck sweep with backoff) → the same job table, different `job_type`, with `retry_count`/`next_attempt_at` columns doing what the in-memory `_HW_HEALTH_LAST_FAILURE` dict did in Python (and surviving a restart, unlike that dict).

## 8. ISE integration module

Mirror the existing abstraction directly:

```java
interface IseTransport {
    IseResult publishPosture(String mac, String status, String details);
    IseResult publishEnforcement(String mac, EnforcementAction action, String policy);
    boolean reachable();
}
```

One real implementation (`ErsIseTransport`) wrapping HTTP Basic Auth + ERS REST calls (endpoint lookup/PUT custom attributes, ANC apply/clear, MNT session lookup + CoA reauth) — functionally identical to `ers_transport.py`. Keep `ENFORCEMENT_MODE` (attribute vs anc) as a configurable Spring property. Don't resurrect the pxGrid stub unless/until it's actually back in scope.

## 9. Frontend (Next.js + TypeScript)

- Mirror the existing page set: Dashboard, Endpoints (+ detail drill-down), Posture/Assessments, Hardware Health, Endpoint 360, Application Remediation, ISE Actions, Audit Logs, Ports, System Health, plus the currently-placeholder pages (Policies, Compliance, Reports, Settings) if/when they get real backends.
- Define shared TypeScript interfaces mirroring your Spring Boot DTOs (e.g. `Endpoint`, `Assessment`, `HardwareHealthReport`) so the API boundary is type-checked on both sides.
- Keep Next.js strictly as the presentation layer — no business logic in Next.js API routes; everything goes through Spring Boot.
- You can reuse the *behavioral* patterns already proven in `dashboard.html` (connection-state badge independent of posture badge, explicit confirm dialogs before Share/Restrict/Clear, an audit log page, per-endpoint expandable detail) even though the implementation will be entirely new.

## 10. Migration sequencing (recommended order)

1. **Freeze and document** current Python behavior (this document + the existing project-plan/DB-reference files) before writing any Java.
2. **Stand up Spring Boot beside Python**, same PostgreSQL, but let Python remain the system of record until Spring Boot's read/write paths are proven — don't have both mutate the same tables blindly.
3. **Entities/repositories/DTOs first** (Endpoint, Assessment, CheckResult, Application, Port, Process, Session, HardwareHealth, Audit), with Flyway migrations.
4. **Reproduce the critical REST endpoints** (ingestion, dashboard summary, endpoint detail) and validate against the real PowerShell agents pointed at the new backend.
5. **Move ISE integration** (MNT discovery, ERS posture share, ANC/CoA enforcement, audit logging) into the `ise` module.
6. **Move scheduling/jobs** off flat files and Python threads onto Postgres-backed jobs + Spring scheduling.
7. **Build the Next.js frontend** against the now-stable Spring API.
8. **Decommission the Python backend**, keeping only the PowerShell agents.

## 11. Things a rewrite must not accidentally lose

- The observation/enforcement separation (Section 2.2 of the project plan) — the single most important architectural property of this system.
- Append-only history semantics for `assessments` and the various `*_history` tables — never overwrite, only insert.
- `connected` as an independent fact from posture status (a device can be connected with stale posture, or disconnected with a perfectly good historical result).
- Every ISE action (success or failure) writing to an audit table, unconditionally.
- The `PROTECTED_KEYWORDS` safety net in remediation — never let a rewrite accidentally make critical software (drivers, security agents, OS components) uninstallable.
- Graceful degradation patterns (e.g., Chart.js CDN blocked ≠ whole dashboard breaks; a single failed collector section ≠ the whole report fails) — worth preserving as a UX principle in the new frontend too.

## 12. Pros/cons summary (from the earlier discussion, retained for reference)

**Spring Boot:** stronger enterprise architecture, excellent Postgres/JPA/Flyway tooling, mature security (Spring Security), better long-term maintainability at the cost of more boilerplate and slower initial velocity than the current Python/Flask version.

**Next.js + TypeScript:** strong dashboard-building ecosystem and type safety across the API boundary, at the cost of extra layers versus plain React if Next.js-specific features (SSR/routing conventions) aren't otherwise needed.

**Keep for later, not now:** Redis (job queue/cache) once concurrency genuinely demands it; Kafka only if endpoint volume grows into real event-stream territory (tens of thousands of endpoints, per the original architecture diagram's long-term target — confirm this target before building for it).
