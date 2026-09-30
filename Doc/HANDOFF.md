# Handoff Instructions — VE Compliance Engine (Endpoint Posture Java)

**Read this first, before touching any code.** This document is for whoever, human or AI agent, picks this project up next. It says what state things are in, what has been verified versus assumed, which mistakes were already made and fixed (do not repeat them), and what to do next.

Last updated: 30 September 2026.

Companion docs: `PROJECT_COMPLETE_GUIDE.md` (the full reference, and the authority when documents disagree), `FEATURE_ROADMAP.md` (ordered backlog and delivery waves), `ROADMAP.md` (infrastructure phases 4 to 9).

---

## 1. What this project is

An agentless endpoint posture and compliance platform integrating with Cisco ISE. Backend: Spring Boot 3.5 (Java 21, Maven). Frontend: Next.js 16, React 19, TypeScript, Tailwind. It runs on a Windows laptop with WinRM/CIM line-of-sight to endpoints on the same LAN, plus access to a live lab ISE instance.

**Non-negotiable architectural rule, enforced in code, not just convention:** posture ingestion must never call ISE. "Share posture", "Restrict" and "Clear restriction" are three separate, explicit, operator-triggered actions, each independently audited, success or failure, unconditionally. Do not collapse them together for convenience, ever.

---

## 2. Repository layout (current)

```
endpoint-posture-java/
├── backend/
│   ├── pom.xml, run.ps1 (RBAC smoke test), start-dev.ps1
│   └── src/
│       ├── main/java/com/endpointposture/
│       │   ├── config/ security/ endpoint/ job/ posture/ hardware/
│       │   │   ise/ session/ audit/ inventory/ dashboard/ policy/ system/
│       ├── main/resources/  application.yml, application-dev.yml (git-ignored), db/migration V1..V13
│       └── test/java/...
├── frontend/
│   ├── lib/          api.ts, auth.ts, permissions.ts, hooks
│   ├── components/   layout, ui, dashboard
│   └── app/          login, (dashboard)/...
├── scripts/          posture_agent.ps1, hardware_health_agent.ps1, Save-PostureCredential.ps1
├── docker-compose.yml   Postgres + Adminer
└── .github/workflows/ci.yml
```

---

## 3. How to run everything, from zero

```powershell
# 1. Postgres + Adminer
docker compose up -d

# 2. One-time agent credential (same Windows account that runs the backend)
.\scripts\Save-PostureCredential.ps1

# 3. Backend, dev profile (values come from the git-ignored application-dev.yml)
cd backend
.\start-dev.ps1
# Expect: Flyway applies V1..V13, "Started EndpointPostureApplication".
# API on :8090, Swagger at http://localhost:8090/swagger-ui.html

# 4. Frontend (separate terminal)
cd frontend
npm install     # first time only
npm run dev     # http://localhost:3000, proxies /api/* to :8090
```

**Secrets have no defaults.** Without the dev profile the app refuses to start unless `JWT_SECRET`, `SEED_ADMIN_PASSWORD` and `POSTURE_API_KEY` are set (plus `ISE_BASE_URL`, `ISE_USERNAME`, `ISE_PASSWORD` if ISE is used). Real values live in `application-dev.yml`, never in this document. Confirm that file is not tracked by git.

Running an agent by hand needs the same key:
```powershell
$env:POSTURE_API_KEY = "<value of app.posture.api-key>"
cd scripts
.\posture_agent.ps1
.\hardware_health_agent.ps1
```

**PowerShell gotcha:** variables (`$token`, `$headers`, `$myId`) do not persist across terminal windows. An unexplained `403` or a double slash in a URL is almost always a stale or empty variable, not a backend bug. Log in again at the top of the session.

**Frontend gotchas:** after big file changes delete `frontend/.next` and restart. The theme is a `theme` cookie, so clear the cookie to reset it.

---

## 4. What has been verified

Verified against real infrastructure (a Windows laptop as target, a real lab ISE):

- Auth: login success, `401` on bad password
- Endpoint discovery, MAC-keyed upsert, ISE-driven connect/disconnect with grace period
- Full posture check through the real `JobWorker` -> `ProcessBuilder` -> PowerShell path; assessments and inventory stored and readable
- Hardware health with real scoring and recommendations
- ISE actions against real ISE: Share Posture, Restrict (`REAUTH_APPLIED`) and Clear all succeeded and were audited
- Worker pool, automatic rechecks, stale-job recovery, application policy versions, system health, inventory retention

Covered by automated tests (`mvn test`): scorers, status ordering, MAC normalization, JWT and both security filters, watcher grace logic, `enqueueIfDue`, recheck scheduler, stale recovery, policy service, user service rules, system health rules, dashboard summary, ISE audit rule, `RESULT_JSON` parsing, and Testcontainers tests for concurrent job claiming, inventory retention and role-based access. Several need Docker running.

**Not verified by me at handoff time:** the latest edits (role-gated bulk buttons, `/ise/status` extra fields, theme cookie, `204` handling in `api.ts`, `UserServiceTest`). After pulling them, run `mvn test` and `npx tsc --noEmit` and click through the dashboard once.

---

## 5. Roles

| Action | ADMIN | OPERATOR | ANALYST | VIEWER |
|---|---|---|---|---|
| Read everything | yes | yes | yes | yes |
| Enqueue checks, share posture | yes | yes | yes | no |
| Restrict / clear | yes | yes | no | no |
| Edit policy, manage users | yes | no | no | no |

The role is read from the database on every request. Users cannot demote, disable or delete themselves, and the last enabled admin cannot be removed. Passwords need 12+ characters. `backend/run.ps1` is a manual smoke test for this (fill in its placeholders first; it deliberately skips Restrict).

---

## 6. Real bugs already found and fixed: do not reintroduce

| # | Bug | Symptom | Fix |
|---|---|---|---|
| 1 | `${ISE_BASE_URL=...}` instead of `:` in `application.yml`, plus a duplicate `app:` key | ISE calls silently no-op'd | Always use `:` for Spring defaults; keep exactly one `app:` root key |
| 2 | `IseSessionClient` had no TLS override | `PKIX path building failed` every 15 s | Same trust-all client as `ErsIseTransport`, only when `verify-tls: false` |
| 3 | ERS calls sent no `Accept`/`Content-Type` | `415` from ISE on every share/restrict/clear | JSON headers on every `/ers/config/**` call; MNT calls use XML instead |
| 4 | `app.jobs.workers.enabled` was indented under `app:` | setting silently ignored | It belongs under `app.jobs:` |
| 5 | `request()` in `api.ts` parsed a `204` body | JSON error on "no hardware report yet" | `204` returns `null`; `latestHardwareOrNull` uses it |
| 6 | `request()` preferred Spring's `error` over `message` | banner said only "Bad Request" | `message` wins |
| 7 | Empty ISE list treated as "nobody connected" | outage marked every device disconnected | `SessionPoll` separates failure from empty; failure freezes state |
| 8 | Two places normalized app names differently (Python era) | stale rows | one normalization function per concept |

If you see a `415`, a `PKIX` error, or ISE calls doing nothing, check `application.yml`, `ErsIseTransport` and `IseSessionClient` first.

---

## 7. Known gaps, not bugs: do not "fix" without confirming

- **`batteryScore` is null** on the test laptop. `BatteryStaticData` returns "Generic failure" (WMI/driver level). `BatteryScorer` returning `null`, not `0`, for "no data" is correct.
- **`enforcement-mode: ATTRIBUTE`** is the default. Under it, Clear removes nothing in ISE; it tells the operator to re-share posture. Switching to `ANC` needs the real ANC policy name confirmed first (default `Quarantine` is unconfirmed).
- **Hardware `/latest` returns `204`** when a device has never been checked. That is intended, not an error.
- **Hardware bands** (85/70/50) and **warranty `UNKNOWN`** are open decisions.
- **Backend cannot run in a Linux container** (PowerShell, CIM/DCOM, DPAPI). Decide hosting before writing a backend Dockerfile.

---

## 8. What to do next, in order

1. **C2 login lockout and rate limiting.** The biggest remaining security gap. Add `failed_attempts` and `locked_until` to `app_user` (new migration V14), lock for a few minutes after 5 failures, keep the identical `401` body, log lockouts.
2. **Docs and CI:** extend `ci.yml` with `next build` (C4).
3. **V5 ISE Actions page** (fleet-wide restriction state derived from the audit trail; show "clear requested", not "unrestricted", in `ATTRIBUTE` mode) and **V4 hardware trend charts.**
4. **C5 metrics**, then **C3 Stage A** (frontend image), then **V6 warranty CSV.**
5. **E1 on-demand diagnostics** job; E2 only if needed.
6. **Remediation R1 -> R2 -> R3 last**, behind a feature flag and a server-side protected-software list.
7. Redis, Kafka, Kubernetes, observability and load testing wait until a concrete need shows up. Do not add them speculatively.

---

## 9. Testing checklist (repeat after any backend change)

```powershell
cd backend
mvn test                       # needs Docker for the Testcontainers tests
cd ..\frontend
npx tsc --noEmit
```

Then, with Postgres, backend and frontend running, log in as `admin`, and check:

- Command Center shows the poll interval and enforcement mode (from `/ise/status`)
- Endpoint detail: Check Posture / Check Hardware enqueue and complete; a never-checked device shows "No hardware report" with no console error
- Share Posture appears in the audit page; log in as a `VIEWER` and confirm the buttons are disabled and a direct API call returns `403`
- Users page: create with a short password is blocked, self row is locked, last admin cannot be removed
- Policies page: saving creates a new version and the next posture check records it

Swagger UI (`/swagger-ui.html`, Authorize with a token) is easier than the CLI for one-off calls.

---

*Update sections 4, 6 and 8 as work lands, and keep `PROJECT_COMPLETE_GUIDE.md` in step. Do not let these drift into aspirational fiction.*