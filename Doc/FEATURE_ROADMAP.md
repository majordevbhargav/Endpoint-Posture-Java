# VE Compliance Engine: Feature Roadmap

Version 2.0, October 2026. Companion to `PROJECT_COMPLETE_GUIDE.md`, `DECISIONS.md` and `ROADMAP.md` (infrastructure).

Scope: application features and hardening. Application remediation is **out of scope** (`DECISIONS.md` D1).

The rule that governs everything: `OBSERVATION -> EVIDENCE -> HUMAN REVIEW -> OPTIONAL ISE ACTION`. No item may cause an ISE action as a side effect of collecting data.

Effort: XS under half a day, S about 1 day, M 2 to 4 days, L 1 to 2 weeks, XL more than 2 weeks.

---

## 1. Done (verified by reading the code)

| ID | Item | Evidence |
|---|---|---|
| H1 | Fail fast on default secrets | `application.yml` secrets have no defaults; dev values in git-ignored `application-dev.yml` |
| H2 | Stale `RUNNING` job recovery | `StaleJobRecoveryScheduler`, covers all four job types |
| H3 | Test baseline | broad unit and Testcontainers suite (no frontend tests) |
| H4 | Documentation cleanup | this pass |
| H5 | Inventory retention | `InventoryRetentionService` and scheduler |
| A1 | Worker pool | `JobWorkerPool` |
| A2 | Automatic rechecks | `RecheckScheduler`, interval and failure backoff from the database |
| A3 | Disconnect grace period | `app.ise.disconnect-grace-polls` |
| V1 | Session history | endpoint, endpoint tab |
| V2 | Dashboard summary, trend, categories | `dashboard/` |
| V3 | System Health | `system/`, `/system` page |
| V4 | Hardware trend chart | multi-series `TrendChart` on the endpoint page |
| V5 | ISE Actions page | `IseActionStateService`, `/ise-actions` |
| V6 | Warranty CSV | V15, `warranty/`, `/warranty` |
| P1 | Policy management | V13, `policy/`, `/policies` |
| C1 | RBAC | four roles, `@PreAuthorize`, user management, `RbacAccessTest` |
| C2 | Login lockout | V14, `LoginAttemptService`, `AuthLockoutTest` |
| C3a | Frontend container | `frontend/Dockerfile`, compose service |
| C4a | CI: tests and frontend build | `ci.yml` |
| C5 | Metrics | `JobQueueMetrics`, `/actuator/prometheus` (admin) |
| E1 | On-demand diagnostics | V16, `diagnostic/`, `diagnostic_agent.ps1`, Diagnostics tab |
| E2 | Security indicators | V17, `indicator/`, `security_indicator_agent.ps1`, tab |

Removed: R1, R2, R3 (remediation).

---

## 2. Next, in order

### Step 1. Freeze scope and sync docs (half a day): done by this pass
Docs at V1 to V17, finished features marked, remediation removed, sharing and deployment decisions recorded in `DECISIONS.md`.

### Step 2. Cheap fixes (1 to 2 days)

| ID | Item | Effort |
|---|---|---|
| S1 | Bound the job list: retention for old `COMPLETE` jobs (for example 30 days), limit or paginate `GET /jobs` | S |
| S2 | Fix N+1 in `AssessmentService.toResponse`: fetch checks for many assessments with one `IN (...)` query | S |
| S3 | `latestPostureOrNull` should call `/posture/latest` and handle the `404` (or add a `204`), instead of downloading history | XS |
| S4 | Reconnect `enqueueIfDue(id, type)` needs a minimum gap (for example skip if the last check completed under 1 hour ago) | XS |

### Step 3. Refactor for the deployment path (about 1 day)

| ID | Item | Effort |
|---|---|---|
| S5 | Extract `AgentRunner` from `JobWorker` (`LocalProcessAgentRunner` only); add `shard` column to `posture_job` | S |

### Step 4. Prove the scale on this machine (M to L)

| ID | Item |
|---|---|
| S6 | Simulator: fake ISE client returning 20k sessions, seed script for 50k endpoints, fake agent runner that sleeps about 20 s and POSTs a synthetic report, k6 or script driver. Measure watcher tick time, queue depth through a simulated morning, DB growth per day, dashboard query latency. |

This replaces every estimate in `ROADMAP.md` section 4 with a number. Do it before the fixes in step 5.

### Step 5. Fix what the numbers expose (expected, not yet measured)

| ID | Item | Why |
|---|---|---|
| S7 | Rewrite `IseSessionWatcher.tick()` as a set diff: one projection query for connected MACs, compare to the poll, batch-write only appeared and disappeared devices | per-session lookups and saves, plus loading every connected entity, will exceed the 15 s tick at 20k sessions |
| S8 | `DashboardService.summary()` as one SQL aggregate; add `latest_assessment_id` on `endpoint` | currently loads all connected endpoints and all latest assessments into memory |
| S9 | Trend as a daily rollup table or materialized view | `trend` cross-joins `endpoint` with days and runs a lateral subquery per pair |
| S10 | Server-side paging and search for `/endpoints`, `/posture/latest`, `/hardware-health/latest`, `/jobs`, and the Topbar search | pages load full lists and filter in the browser |
| S11 | Monthly partitioning of `assessment`, `check_result`, `endpoint_session_log`, `posture_job`; drop old partitions; decide how long "evidence forever" really is | about 120k assessments per day at the target |

### Step 6. ISE feed (small, after D3 is confirmed)

| ID | Item |
|---|---|
| X1 | `ise_sharing_rule` (versioned, ADMIN, audited), `GET /api/v1/integrations/ise/endpoints` with its own scoped key, `PosturePublisher` interface. Verify pxGrid Direct limits first. See `DECISIONS.md` D3. |

### Step 7. Harden and deploy (Option A)

See section 4.

---

## 3. Findings from reading the code (not measured)

The earlier findings F1 to F12 are all resolved except where listed in step 2 and step 5 above. The scale findings come from code structure only; no benchmark has been run. Treat them as hypotheses that S6 confirms or kills.

---

## 4. Production hardening checklist

| Item | Detail |
|---|---|
| Secrets | Real values only in environment or a secrets store; rotate the single shared `POSTURE_API_KEY`; move lab values out of `application-dev.yml` if the repo is ever shared |
| ISE TLS | `verify-tls: true` once a trusted certificate exists |
| Swagger | Disable or restrict `/swagger-ui/**` and `/v3/api-docs` to ADMIN outside dev (currently public) |
| TLS | HTTPS for the API as soon as anything crosses a machine boundary (also required for a remote runner) |
| Rate limiting | Per-IP limiter on `/auth/login` (Bucket4j is enough for one instance); account lockout alone allows lock-out denial of service |
| Browser security | Security headers and CSP at the reverse proxy; the JWT is in `sessionStorage` |
| Agent credential | Least-privilege service account (`DECISIONS.md` D7) |
| Logging and backups | Structured logging, log shipping, Postgres backup and restore test |
| CI | Image build and push to GHCR (C4b) |
| Tests | Fixture tests for `IseSessionClient` XML parsing and `ErsIseTransport`; frontend tests |
| Small | Remove the unused `trend` variable in `DiagnosticsTab` |

---

## 5. Requirement matrix for what is left

| Item | Needs first | Migration | Needs live ISE / endpoint to test |
|---|---|---|---|
| S1 to S4 | none | none (S1 may add an index) | no |
| S5 | none | one column | no |
| S6 | S5 helpful | none | no (that is the point) |
| S7 to S9 | S6 | S8 adds a column | no |
| S10 | S6 | none | no |
| S11 | S6 | partitioning migration | no |
| X1 | D3 confirmed, ISE limits checked | one table | ISE |

---

## 6. Deliberately not planned

Application remediation; automatic enforcement of any kind; pxGrid proper until certificates exist; browser-history collection; Redis, Kafka, Kubernetes and the observability stack until a measured trigger appears (`ROADMAP.md`).

---

*Update the Done table and the step list as items land so this stays a description of the plan, not of the past.*