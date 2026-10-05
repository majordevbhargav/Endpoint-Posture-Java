# Handoff: VE Compliance Engine (Endpoint Posture Java)

**Read this first.** It says what state things are in, what is verified versus assumed, which mistakes were already fixed (do not repeat them), and what to do next.

Last updated: October 2026.

Companion docs: `PROJECT_COMPLETE_GUIDE.md` (full reference, the authority when documents disagree), `DECISIONS.md`, `FEATURE_ROADMAP.md`, `ROADMAP.md`.

---

## 1. What this is

An agentless endpoint posture and compliance platform integrating with Cisco ISE. Backend Spring Boot 3.5 (Java 21, Maven). Frontend Next.js 16, React 19, TypeScript, Tailwind. Runs on a Windows machine with WinRM/CIM line-of-sight to endpoints and access to a lab ISE.

**Non-negotiable rule, enforced in code:** posture ingestion never calls ISE. Share, Restrict and Clear are separate, explicit, audited actions (success or failure). Do not collapse them. Standing sharing rules are a proposed, unbuilt extension (`DECISIONS.md` D3); Restrict and Clear stay per-device actions.

**Out of scope:** application remediation, automatic enforcement.

---

## 2. Layout

```
endpoint-posture-java/
├── backend/   config security endpoint job posture hardware ise session audit
│              inventory dashboard policy system warranty diagnostic indicator
│              + resources: application.yml, db/migration V1..V17
├── frontend/  app, components, lib, Dockerfile
├── scripts/   posture_agent, hardware_health_agent, diagnostic_agent,
│              security_indicator_agent, Save-PostureCredential
├── docker-compose.yml   Postgres, Adminer, optional frontend
├── docs/archive/        retired documents
└── .github/workflows/ci.yml
```

---

## 3. Run from zero

```powershell
docker compose up -d
.\scripts\Save-PostureCredential.ps1      # same Windows account that runs the backend

cd backend
.\start-dev.ps1                           # expect Flyway to apply V1..V17; API :8090

cd ..\frontend
npm install                               # first time
npm run dev                               # http://localhost:3000
```

**Secrets have no defaults.** Without the dev profile set `JWT_SECRET`, `SEED_ADMIN_PASSWORD`, `POSTURE_API_KEY`, `DB_PASSWORD` (plus `ISE_BASE_URL`, `ISE_USERNAME`, `ISE_PASSWORD` if ISE is used). Real values live in the git-ignored `application-dev.yml`. Confirm it is not tracked.

Manual agent run:
```powershell
$env:POSTURE_API_KEY = "<value of app.posture.api-key>"
cd scripts ; .\posture_agent.ps1 ; .\hardware_health_agent.ps1
```
The diagnostic and security agents also need `-Mac` when targeting a remote host, and a workgroup target reached by IP needs the host's WinRM `TrustedHosts` entry.

**Gotchas.** PowerShell variables do not persist across windows; an unexplained `403` or a double slash in a URL is almost always a stale variable, so log in again. After large frontend changes delete `frontend/.next`. Clear the `theme` cookie to reset the theme.

---

## 4. What has been verified

Against a real Windows laptop and a real lab ISE: login and `401`; endpoint discovery and connect/disconnect with grace period; full posture check through `JobWorker` to PowerShell; hardware health with real scoring; ISE Share (`Posture shared with ISE as COMPLIANT`), Restrict (`REAUTH_APPLIED`) and Clear, all audited; worker pool, rechecks, stale recovery, policy versions, system health, inventory retention.

Covered by automated tests (`mvn test`, several need Docker): see `PROJECT_COMPLETE_GUIDE.md` section 12.

**Not recorded as verified live:** Endpoint 360 diagnostics, security indicators, warranty upload, login lockout in the browser, `/ise-actions` page, the frontend container. They have tests or code, but no live run is documented. After pulling, run `mvn test` and `npx tsc --noEmit`, click through every page, run one diagnostic and one security scan against a real endpoint, and record the result here.

**Never measured:** behaviour at scale (`FEATURE_ROADMAP.md` S6).

---

## 5. Roles

| Action | ADMIN | OPERATOR | ANALYST | VIEWER |
|---|---|---|---|---|
| Read everything | yes | yes | yes | yes |
| Enqueue checks, share posture | yes | yes | yes | no |
| Restrict / clear | yes | yes | no | no |
| Edit policy, upload warranty, manage users | yes | no | no | no |

Role is read from the database on every request. Users cannot demote, disable or delete themselves; the last enabled admin cannot be removed. Passwords need 12+ characters; five wrong passwords lock an account for five minutes. `backend/run.ps1` is a manual RBAC smoke test (fill in its placeholders; it skips Restrict on purpose).

---

## 6. Real bugs already found and fixed: do not reintroduce

| # | Bug | Symptom | Fix |
|---|---|---|---|
| 1 | `${ISE_BASE_URL=...}` instead of `:` in `application.yml`, plus a duplicate `app:` key | ISE calls silently did nothing | Use `:` for defaults; exactly one `app:` root key |
| 2 | `IseSessionClient` had no TLS override | `PKIX path building failed` every 15 s | Same trust-all client as `ErsIseTransport`, only when `verify-tls: false` |
| 3 | ERS calls sent no `Accept`/`Content-Type` | `415` on every share/restrict/clear | JSON headers on every `/ers/config/**` call; MNT calls use XML |
| 4 | `app.jobs.workers.enabled` indented under `app:` | setting ignored | belongs under `app.jobs:` |
| 5 | `request()` in `api.ts` parsed a `204` body | JSON error on "no hardware report yet" | `204` returns `null` |
| 6 | `request()` preferred Spring's `error` over `message` | banner said only "Bad Request" | `message` wins |
| 7 | Empty ISE list treated as "nobody connected" | an outage marked every device disconnected | `SessionPoll` separates failure from empty; failure freezes state |
| 8 | Two places normalized app names differently (Python era) | stale rows | one normalization function per concept |
| 9 | `app.security.login.*` nested under `app.jobs` | lockout settings ignored | must sit directly under `app.security`; `ApplicationConfigBindingTest` guards it |

If you see `415`, `PKIX`, or ISE calls doing nothing, check `application.yml`, `ErsIseTransport` and `IseSessionClient` first.

---

## 7. Known gaps, not bugs: do not "fix" without confirming

- `batteryScore` is null on the test laptop (`BatteryStaticData` returns "Generic failure"). Null, not zero, is correct.
- `enforcement-mode: ATTRIBUTE` is the default; Clear removes nothing in ISE. `ANC` needs the real policy name confirmed.
- Hardware `/latest`, diagnostics `/latest` and indicators `/latest` return `204` when never run. Intended.
- Hardware bands (85/70/50) and indicator thresholds are illustrative.
- `WINRM_UNAVAILABLE` is a non-failing result, not an error. `FAILED` is reserved for the worker; agents may not submit it.
- The backend cannot run in a Linux container (PowerShell, CIM/DCOM, DPAPI). Deployment model: `DECISIONS.md` D2.

---

## 8. What to do next, in order

1. **Confirm the open decisions** in `DECISIONS.md`: D2 (deployment), D3 (sharing option B and the rule amendment), D4 (scale approach).
2. **Cheap fixes** (`FEATURE_ROADMAP.md` S1 to S4): bound the job list, remove the N+1 in `AssessmentService.toResponse`, stop `latestPostureOrNull` downloading history, add a minimum gap to reconnect rechecks.
3. **`AgentRunner` extraction and `shard` column** (S5).
4. **Scale simulator** (S6), then fix what it exposes (S7 to S11).
5. **ISE feed** (X1) once D3 is confirmed and pxGrid Direct limits are checked.
6. **Harden and deploy** with Option A (`FEATURE_ROADMAP.md` section 4).
7. Redis, Kafka, Kubernetes and the observability stack wait for a measured trigger. Do not add them speculatively.

---

## 9. Testing checklist (after any backend change)

```powershell
cd backend ; mvn test         # Docker needed for Testcontainers
cd ..\frontend ; npx tsc --noEmit ; npm run build
```

Then with Postgres, backend and frontend running, log in as `admin` and check:

- Command Center shows poll interval and enforcement mode.
- Endpoint page: Check Posture, Check Hardware, Run Diagnostics and Run Security Scan enqueue and complete; a never-checked device shows an empty state with no console error.
- Share Posture appears in the audit page; as a `VIEWER` the buttons are disabled and a direct API call returns `403`.
- Users page: short password blocked, own row locked, last admin cannot be removed.
- Policies page: saving creates a new version and the next posture check records it.
- Warranty page: CSV upload as admin works; as another role it is disabled and the API returns `403`.
- Five wrong passwords lock the account; the response is identical to a wrong password.

Swagger UI (`/swagger-ui.html`, Authorize with a token) is easier than the CLI for one-off calls.

---

*Update sections 4, 6 and 8 as work lands, and keep `PROJECT_COMPLETE_GUIDE.md` in step.*