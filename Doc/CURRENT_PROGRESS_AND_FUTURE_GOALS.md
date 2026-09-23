# VE Compliance Engine — Current Progress & Future Goals

Document version: 1.0
Status: Living document — update as phases complete
Companion to: `ROADMAP.md`, `SYSTEM_OVERVIEW.md`, `VE_Compliance_Engine_HLD_LLD_Complete.md`

This document captures **where the Spring Boot rewrite actually stands right now** —
every module built and verified end-to-end against real data (your own laptop
and a real Cisco ISE instance) — and lays out everything still needed to take
this from a working backend to a live, production-shaped system.

---

## 1. Current status — what's real and verified

Unlike the original Python prototype, every module below has been exercised
against **live data**: your own Windows laptop as a real posture/hardware
target, and a real Cisco ISE instance at `10.6.1.90` for session discovery
and enforcement actions. Nothing here is "should work" — it's "ran, and the
response was inspected."

### Infrastructure
- ✅ PostgreSQL 16 running in Docker (`ep-postgres`, port `5434`), data
  persisted in a named volume
- ✅ Adminer running in Docker (`ep-adminer`, port `8081`) for direct DB
  inspection
- ✅ Flyway-managed schema, 11 migrations applied cleanly (`V1`–`V11`), real
  foreign keys throughout — no MAC-string-only logical relationships like
  the old Python schema had
- ✅ `application.yml` fully wired: datasource, JWT, seeded admin, job
  polling, posture/hardware agent config, ISE connection + enforcement mode

### Security (`security/`)
- ✅ JWT login (`POST /api/v1/auth/login`), seeded admin user on first boot
- ✅ `JwtAuthFilter` protecting every route except `/auth/**` and health/Swagger
- ✅ `PostureApiKeyFilter` — separate shared-secret auth path scoped tightly
  to the two agent ingestion routes only (`POST /api/v1/posture`,
  `POST /api/v1/hardware-health`)
- ✅ Verified: valid login returns a working token; bad password returns 401

### Endpoint domain (`endpoint/`)
- ✅ MAC-keyed upsert (`upsertByMac`), normalized MAC storage
  (`AA:BB:CC:DD:EE:FF`)
- ✅ `GET /api/v1/endpoints`, `GET /api/v1/endpoints/{id}`
- ✅ Verified: 5 real endpoints discovered via ISE session polling, IPs and
  connection state populated live

### Job queue (`job/`)
- ✅ `posture_job` table, `SELECT ... FOR UPDATE SKIP LOCKED` claim pattern
  (concurrency-safe — no double-run risk)
- ✅ `JobWorker` — `@Scheduled` poll every 3s, dispatches real PowerShell
  agents via `ProcessBuilder`, outer timeout as a backstop above each
  agent's own internal timeouts
- ✅ Retry with exponential-ish backoff on failure, `FAILED` after
  `max_attempts`
- ✅ Verified end-to-end: enqueue → `QUEUED` → `RUNNING` → `COMPLETE`, both
  for `POSTURE_CHECK` and `HARDWARE_CHECK` job types

### Posture (`posture/`)
- ✅ `Assessment` / `CheckResult` entities, append-only, real FKs
  (`check_result.assessment_id → assessment.id ON DELETE CASCADE`)
- ✅ `PostureIngestController` (write) and `PostureQueryController` (read)
  deliberately separate classes/beans — ingestion can never fall through
  into an ISE call
- ✅ `overallStatus()` correctly picks the worst status across all checks
  using the `AssessmentStatus` severity ordering
  (`COMPLIANT < NON_COMPLIANT < ERROR`)
- ✅ Verified live: real firewall/ports/applications check against your own
  laptop, 95 real installed applications collected, full JSON stored as
  JSONB with a GIN index

### Hardware health (`hardware/`)
- ✅ `HardwareHealthReport` / `HardwareRecommendation` entities
- ✅ Four component scorers (CPU, Memory, Storage, Battery) — Battery is
  nullable by design (a desktop with no battery is "not applicable," not
  a zero score dragging the average down)
- ✅ Verified live: real overall score computed (52, band `DEGRADED`),
  recommendations generated from real Windows Event Log data (1187
  hardware-relevant events in 7 days)
- 🟡 **Known gap, not a bug**: `batteryScore` is coming back empty on the
  test laptop. Confirmed via direct `Get-CimInstance -Namespace root/wmi
  -ClassName BatteryStaticData` → `Generic failure`. This is a
  Windows/WMI-level limitation on this specific machine, not an issue in
  `BatteryScorer` or the ingestion path.

### ISE session watcher (`session/`)
- ✅ `IseSessionWatcher` — `@Scheduled` poll of ISE's MNT ActiveList
- ✅ TLS handling fixed to match `ErsIseTransport` (insecure trust-all
  client when `app.ise.verify-tls: false`, for lab/self-signed ISE certs)
- ✅ Connect/disconnect state tracked independently of posture status
- ✅ Auto-enqueues a `POSTURE_CHECK` job on reconnect, `enqueueIfDue()`
  guards against flooding the queue every 15s for the same endpoint
- ✅ Verified live: real ISE sessions polled every 15s, endpoints flip
  `connected: true/false` correctly, reconnect triggers a real recheck job
- 🟡 **Known gap, not urgent**: an ISE session with no IP (`EC:1C:5D:66:C0:CB`)
  causes `JobWorker` to fail its dispatch with a clear `IllegalStateException`
  (caught, logged, retried with backoff, eventually `FAILED`) — cosmetic log
  noise, not a crash. Optional fix: skip enqueueing for endpoints with
  neither IP nor hostname.

### ISE actions (`ise/`, `audit/`)
- ✅ `IseTransport` interface + `ErsIseTransport` (ERS REST implementation)
- ✅ Three structurally separate, explicitly operator-triggered actions:
  Share Posture, Restrict, Clear Restriction — never called as a side
  effect of posture ingestion (the platform's non-negotiable architectural
  rule, enforced in code, not just convention)
- ✅ Fixed real bug: ISE's ERS API requires explicit `Accept`/`Content-Type`
  headers — missing them caused a `415 Unsupported Media Type` on every
  ERS call. Fixed in `ErsIseTransport`.
- ✅ Every action — success or failure — writes exactly one audit row,
  unconditionally
- ✅ Verified live against real ISE: **Share Posture succeeded** ("Posture
  shared with ISE as COMPLIANT"), **Restrict succeeded** ("REAUTH_APPLIED"),
  **Clear succeeded**. All three audit rows confirmed via
  `GET /api/v1/audit/ise-actions`.

### API documentation
- ✅ Swagger UI live at `/swagger-ui.html`, JWT "Authorize" button wired,
  every endpoint testable interactively

---

## 2. What this proves

Every module in the originally planned backend architecture —
`security → endpoint → job → posture → hardware → session → ise → audit`
— is not just written, it is **proven working against real infrastructure**:
a real Windows endpoint and a real Cisco ISE deployment. This is a materially
stronger position than the original Python prototype ever reached, and
closes out every item in the "Immediate next steps" and "Medium-term"
sections of the old roadmap (operator authentication, real ISE environment
confirmation, the full share/restrict/clear loop).

---

## 3. Small cleanups worth doing (not blockers)

| # | Item | Priority |
|---|---|---|
| 1 | `JwtService` — replace deprecated `signWith(Key, SignatureAlgorithm)` with `signWith(Key)` | Low |
| 2 | `IseSessionWatcher`/`JobWorker` — skip enqueueing a job for endpoints with no IP and no hostname, instead of letting it fail and retry | Low |
| 3 | Investigate `BatteryStaticData` WMI failure on the test laptop (likely OEM/driver-specific, not universal) | Low |
| 4 | Bump Spring Boot parent from 3.5.6 → 3.5.16 (OSS support for 3.5.x has a documented end date) | Low |
| 5 | Clean up stray `hs_err_pid*.log` / `replay_pid*.log` crash dumps, add to `.gitignore` | Low |
| 6 | Confirm real ANC policy name in ISE if switching `enforcement-mode` from `ATTRIBUTE` to `ANC` | Medium (only if ANC mode is needed) |
| 7 | Add a stale-`RUNNING`-job recovery sweep (if the backend restarts mid-job, that job stays `RUNNING` forever) | Medium |

---

## 4. What's left before this can be called "live"

### Phase 2 — Frontend (Next.js + TypeScript) — not started
- [ ] Scaffold Next.js app (App Router, TypeScript)
- [ ] Login page → stores JWT, attaches `Authorization: Bearer` to all API calls
- [ ] Endpoint list + detail drill-down (mirrors `dashboard.html`'s old
      "Endpoints" page — connection badge independent of posture badge)
- [ ] Posture/Assessments history view, per-endpoint
- [ ] Hardware Health view — scores, band, recommendations, trend
- [ ] ISE Actions page — Share/Restrict/Clear buttons with confirm dialogs,
      inline success/failure feedback (reuse the UX pattern already proven
      in the old `dashboard.html`)
- [ ] Audit Logs page — reads `/api/v1/audit/ise-actions`, filterable by
      endpoint
- [ ] Jobs page — live status of queued/running/complete/failed jobs
- [ ] Shared TypeScript interfaces mirroring the Spring Boot DTOs
      (`Endpoint`, `Assessment`, `HardwareHealthResponse`, `IseActionAudit`,
      etc.) so the API boundary is type-checked on both sides
- [ ] Next.js stays strictly presentation-layer — no business logic in
      Next.js API routes, everything goes through the Spring Boot API

### Phase 3 — Containerize — not started
- [ ] Multi-stage `Dockerfile` for the Spring Boot backend (build jar →
      slim JRE runtime image)
- [ ] `Dockerfile` for the Next.js frontend (`output: 'standalone'`, not
      the dev server)
- [ ] Extend `docker-compose.yml` to run backend + frontend + Postgres
      together as one command — becomes both the dev environment and a
      dry run for the later Kubernetes manifests

### Phase 4 — Redis + Kafka, justified not decorative — not started
- [ ] **Redis** (`redis:7-alpine`) — first concrete use: cache the
      dashboard's endpoint-summary query, or a lock key so `session/` and
      `job/` never double-trigger a check for the same endpoint at the
      same moment
- [ ] **Kafka** (Redpanda or Bitnami, lighter for laptop-scale) — one topic
      (`posture-events`), published after a successful ingest, consumed by
      a simple audit-logger — proves the event-driven pattern before
      leaning on it for real dispatch-fleet work
- [ ] Neither is added until a specific, felt problem justifies it — not
      "because enterprise"

### Phase 5 — Dispatcher fleet simulation (lab scale) — not started
- [ ] Kafka topic `posture-jobs`, partitioned by subnet (3–4 partitions
      demonstrates the pattern)
- [ ] 2–3 lightweight local consumer processes as "dispatcher" instances,
      each owning a partition, each doing real WinRM against whatever real
      lab endpoints exist
- [ ] Proves the sharding model that would eventually support the
      50,000-endpoint long-term target — same code/topic design, just
      fewer workers at lab scale

### Phase 6 — Local Kubernetes — not started
- [ ] `kind` or `minikube` cluster
- [ ] Deployments for backend/frontend pods
- [ ] Postgres as an external service or StatefulSet; Helm charts for
      Redis (Bitnami) and Kafka/Redpanda
- [ ] `ingress-nginx` Helm chart as the gateway (makes the edge-request-flow
      diagram in `ROADMAP.md` real, running locally)

### Phase 7 — Observability — not started
- [ ] `kube-prometheus-stack` Helm chart (Prometheus + Grafana +
      Alertmanager in one release)
- [ ] Micrometer + Actuator exposed at `/actuator/prometheus`
- [ ] Grafana panels: request latency, job-queue depth, JVM heap, Kafka
      consumer lag (once Kafka exists)
- [ ] One Alertmanager rule (e.g. "job queue depth > N for 5 minutes") to
      prove the pipeline end-to-end

### Phase 8 — CI/CD — partially started
- ✅ `.github/workflows/ci.yml` exists and passes
- [ ] Extend it: run tests → build Docker images → push to GHCR
- [ ] Optional: self-hosted Argo CD inside the same `kind` cluster for
      GitOps-style auto-sync

### Phase 9 — Load testing — not started
- [ ] `k6` scripts against the local ingress at whatever concurrency one
      laptop can push
- [ ] Won't hit real 20k-user / 50k-endpoint scale, but will surface real
      bottlenecks (connection pool sizing, missing indexes, consumer lag)
      that would bite in production regardless

### Deferred modules (explicit non-goals until pulled into scope)
These have schema reserved (per the HLD's `V9__reserve_remediation_and_360_schema.sql`
equivalent) but no application code — intentionally, to avoid speculative
work on features not yet needed:
- [ ] **`remediation/`** — application classification (Business
      Relevant/Irrelevant/Review) + remote uninstall via PowerShell
      Remoting, `PROTECTED_KEYWORDS` safety net carried forward from the
      Python prototype
- [ ] **`endpoint360/`** — experience collector (Wi-Fi, ping, DNS, TCP-443
      probe, traceroute) + security-indicator collector (lateral movement,
      C2 beaconing heuristics), both reused conceptually from the Python
      version's `endpoint_experience_dashboard.py` /
      `endpoint_security_indicators.py`
- [ ] **pxGrid transport** — blocked on ISE pxGrid persona + client
      certificates; stays inert until that's actually available
- [ ] **Full RBAC (4 roles)** — Admin/Security Analyst/Network
      Operator/Viewer — added only once a second role is genuinely
      enforced differently somewhere in the app, not speculatively

### Production-hardening checklist (before anything public-facing)
- [ ] Replace dev JWT secret / seed admin password / posture API key with
      real secrets (env-injected, never committed)
- [ ] `app.ise.verify-tls: true` against a properly certificate-trusted
      ISE deployment (currently `false` for the lab instance)
- [ ] Real ANC policy name confirmed against production ISE, if using ANC
      enforcement mode
- [ ] Rate limiting / abuse protection on `/api/v1/auth/login`
- [ ] Structured logging + log shipping (not just console output)
- [ ] Database backup strategy for the Postgres volume
- [ ] Confirm real scale target (pilot vs. the 40,000-endpoint /
      20,000-session long-term architecture) — determines how urgently
      Phases 4–6 actually need to happen versus how long they can wait

---

## 5. Recommended order from here

1. **Frontend (Phase 2)** — the backend API surface is now stable and
   verified; this is the highest-value next step since there's currently
   no UI at all, only Swagger/CLI testing.
2. **Containerize (Phase 3)** — low effort once the frontend exists, and
   de-risks every phase after it.
3. **Small backend cleanups** (Section 3 above) — cheap, do alongside
   frontend work rather than blocking on them.
4. **Redis + Kafka (Phase 4)** only once a concrete need shows up (e.g.
   real concurrent dashboard load, or starting the dispatcher-fleet
   simulation).
5. Everything from **Kubernetes onward (Phases 5–9)** is real work but
   lower urgency until the frontend + containerized dev loop is proven
   solid.

---

*This is a living document — update the checkboxes and status tags as each
phase actually lands, rather than writing it once and letting it drift from
reality (the same discipline the original `ROADMAP.md` called for).*
