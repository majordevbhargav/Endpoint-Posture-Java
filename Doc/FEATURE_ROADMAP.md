# VE Compliance Engine: Feature Roadmap

Version 3.0, October 2026. Companion to `PROJECT_COMPLETE_GUIDE.md`, `DECISIONS.md` and `ROADMAP.md` (infrastructure).

Scope: application features and hardening. Application remediation is **out of scope** (`DECISIONS.md` D1).

The rule that governs everything: `OBSERVATION -> EVIDENCE -> HUMAN REVIEW -> OPTIONAL ISE ACTION`. No item may cause an ISE action as a side effect of collecting data.

Effort: XS under half a day, S about 1 day, M 2 to 4 days, L 1 to 2 weeks, XL more than 2 weeks.

---

## 1. Done (verified by reading the code and tests)

| ID | Item | Evidence |
|---|---|---|
| H1 | Fail fast on default secrets | `application.yml` secrets have no defaults; dev values in git-ignored `application-dev.yml` |
| H2 | Stale `RUNNING` job recovery | `StaleJobRecoveryScheduler`, covers all four job types |
| H3 | Test baseline | broad unit and Testcontainers suite plus 4 frontend vitest files |
| H4 | Documentation cleanup | repeated each pass |
| H5 | Inventory retention | `InventoryRetentionService` and scheduler |
| A1 | Worker pool | `JobWorkerPool` |
| A2 | Automatic rechecks | `RecheckScheduler`, set-based SQL, per-sweep cap, hardware priority -5 |
| A3 | Disconnect grace period | `app.ise.disconnect-grace-polls` |
| V1 | Session history | endpoint, endpoint tab |
| V2 | Dashboard summary, trend, categories | `dashboard/` |
| V3 | System Health | `system/`, `/system` page |
| V4 | Hardware trend chart | multi-series `TrendChart` |
| V5 | ISE Actions page | `IseActionStateService`, `/ise-actions` |
| V6 | Warranty CSV | V15, `warranty/`, `/warranty` |
| P1 | Policy management | V13, `policy/`, `/policies` |
| C1 | RBAC | four roles, `@PreAuthorize`, user management, `RbacAccessTest` |
| C2 | Login lockout | V14, `LoginAttemptService`, `AuthLockoutTest` |
| C3a | Frontend container | `frontend/Dockerfile`, compose service |
| C4 | CI: tests, frontend build, image push to GHCR | `ci.yml` (lowercase tag, needs backend and frontend) |
| C5 | Metrics | `JobQueueMetrics`, `ise_watcher_tick_seconds`, `/actuator/prometheus` (admin) |
| E1 | On-demand diagnostics | V16, `diagnostic/`, agent, tab |
| E2 | Security indicators | V17, `indicator/`, agent, tab |
| S1 | Job list bounded, 30 day job retention | V18, `JobRetentionScheduler`, `limit` clamp |
| S2 | N+1 fix | `AssessmentService` batches checks (1000 ids per query) |
| S3 | `/posture/latest` for the newest assessment | `PostureQueryController` returns `204` |
| S4 | Reconnect minimum gap | `app.jobs.reconnect-min-gap-minutes` |
| S5 | `AgentRunner` and `shard` | `LocalProcessAgentRunner`, V19 |
| S6 | Scale simulator | `sim/` package, `application-sim.yml`, `scripts/sim/` |
| S7 | Watcher as a set diff | `IseSessionWatcher`, `SessionBatchWriter` |
| S8 | Dashboard from one SQL aggregate | V20 pointers on `endpoint`, `DashboardRepository` |
| S9 | Trend rollup | `compliance_daily`, `TrendRollupScheduler` |
| S10 | Server-side paging and search | `/endpoints/page`, `/hardware-health/page`, `/summary`, `/latest/batch` |
| S12 | Stall and read-path hardening | pool and keep-alive tuning, `touch-min-minutes`, `max-per-sweep`, fleet-list cap, V22 trigram indexes, `spring.task.scheduling.pool.size` |
| B1 | Swagger admin-only unless `app.docs.public` | `SecurityConfig`, `RbacAccessTest` |
| B2 | Per-IP login rate limit (429), idle buckets evicted | `LoginRateLimiter`, tests |
| B3 | Security headers (backend filter and `next.config.mjs`) | `SecurityHeadersFilter` |
| B4 | Startup warnings for `verify-tls=false` and public docs | `StartupSecurityLogger` |
| B5 | Production checklist | `docs/PRODUCTION_CHECKLIST.md` |
| T1 | Tests for ISE session XML parsing and `ErsIseTransport` | `IseSessionClientXmlTest`, `ErsIseTransportTest` |
| T2 | Frontend tests | vitest: csv, session, permissions, usePagination |
| F1 | Fleet-list cap on every unbounded list | `/endpoints`, `/posture/latest`, `/hardware-health/latest`, `/applications`, `/ports` |

Removed: R1, R2, R3 (remediation).

---

## 2. Left, in order

| ID | Item | Needs | Effort |
|---|---|---|---|
| L1 | Live runs: diagnostics, security scan, warranty upload, lockout, `/ise-actions`, frontend container, 429 through the proxy | you, a real endpoint and ISE | S |
| L2 | Post-S12 simulation re-run (30 to 60 min, `-Xlog:gc*`), compare with `ROADMAP.md` section 4 | you; send the SIM lines | S |
| S13 | Paged inventory: `/applications` and `/ports` return one row per app or port for the whole fleet and are capped by F1. Add a latest-inventory pointer on `endpoint` (migration V23) and server-side paging, search and filters | L2 first, to see whether it bites | M |
| S11 | Monthly partitioning of `assessment`, `check_result`, `endpoint_session_log`, `posture_job`; drop old partitions | decision D10 (evidence retention) | L |
| X1 | ISE feed: `ise_sharing_rule`, `GET /api/v1/integrations/ise/endpoints`, `PosturePublisher` | decision D3 and pxGrid Direct limits check | L |
| Deploy | Option A deployment with the production checklist | decisions D2, D7 | M |

---

## 3. Findings

Scale numbers are measured on the simulator, not on real hardware. Pre-S12 results are in `ROADMAP.md` section 4 and `DECISIONS.md` D4; the post-S12 re-run is pending (L2). Two Hikari stalls seen in the pre-S12 run were addressed by S12 settings but not yet proven fixed.

---

## 4. Production hardening checklist (what is still open)

| Item | Detail |
|---|---|
| Secrets | Real values only in environment or a secrets store; rotate the single shared `POSTURE_API_KEY`; rotate any lab value that appeared in an old handoff |
| ISE TLS | `verify-tls: true` once a trusted certificate exists (startup WARN until then) |
| TLS | HTTPS for the API as soon as anything crosses a machine boundary (also required for a remote runner) |
| Rate limiting | Done per IP; behind a proxy set `trust-forwarded-for` correctly (D9) |
| Browser security | CSP still allows `'unsafe-inline'` and `'unsafe-eval'` for Next.js; tighten with nonces. The JWT lives in `sessionStorage` |
| Agent credential | Least-privilege service account (`DECISIONS.md` D7) |
| Logging and backups | Structured logging, log shipping, Postgres backup and restore test |
| CI | Backend image is not built (D2); frontend image is pushed on `main` |
| Tests | No end-to-end test of the frontend against a running backend |

---

## 5. Requirement matrix for what is left

| Item | Needs first | Migration | Needs live ISE / endpoint to test |
|---|---|---|---|
| L1 | none | none | yes |
| L2 | none | none | no |
| S13 | L2 | V23 | no |
| S11 | D10 | partitioning migration | no |
| X1 | D3 confirmed, ISE limits checked | one table | ISE |
| Deploy | D2, D7 | none | yes |

---

## 6. Deliberately not planned

Application remediation; automatic enforcement of any kind; pxGrid proper until certificates exist; browser-history collection; Redis, Kafka, Kubernetes and the observability stack until a measured trigger appears (`ROADMAP.md`).

---

*Update the Done table and the Left table as items land so this stays a description of the plan, not of the past.*