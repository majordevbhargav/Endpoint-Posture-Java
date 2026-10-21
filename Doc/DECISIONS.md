# Decisions

One place for what has been decided, what is recommended but awaiting confirmation, and what is still open. Update the status when something changes.

Status key: **Decided** · **Working default** (adopted, confirm or change) · **Open**

---

## D1. Application remediation is out of scope (Decided)

R1 (classification), R2 (dry-run uninstall) and R3 (real uninstall) are removed from the roadmap. The Java schema never created the reserved remediation tables, so nothing needs dropping. The platform is observe, review, then share/restrict/clear. If this is ever revisited it needs a fresh design, not the old Python-era notes.

## D2. Backend deployment model (Working default)

The backend launches `powershell.exe` and depends on CIM/DCOM, WinRM and a DPAPI-encrypted credential, so it cannot run in a Linux container as built.

| Option | Verdict |
|---|---|
| A. Windows host or VM, jar as a service, Postgres and frontend elsewhere | **Now.** It is what has been verified. |
| B. Windows containers | Rejected: DPAPI and credential handling get awkward, little gain. |
| C. Split: API anywhere (Linux/containers) + small fleet of Windows runner VMs that claim jobs and run the agents | **Production target.** Build only when scale demands it. |
| D. Rewrite collection off PowerShell | Rejected: loses CIM/DCOM, rewrites working code. |

Option A specifics: Windows Server VM with JDK 21; jar as a service (WinSW or NSSM) under the same account that ran `Save-PostureCredential.ps1` (a normal dedicated service user; a gMSA cannot log on interactively to create the DPAPI file); secrets as machine environment variables; firewall open to endpoint VLANs (WinRM/DCOM) and ISE (443); `TrustedHosts` set for workgroup targets; TLS reverse proxy in front of the frontend with the API port closed to everyone else. Details: `docs/PRODUCTION_CHECKLIST.md`.

Prep for C is **done**: process launching lives behind the `AgentRunner` interface (`LocalProcessAgentRunner` is the only real implementation; `SimulatedAgentRunner` exists in the `sim` profile) and `posture_job` has a `shard` column (default `default`, claims do not filter by it yet). A later remote runner claims jobs over HTTPS with its own credential, which requires TLS and a runner key.

## D3. ISE sharing model at scale (Built: option B, awaiting live verification and pxGrid Direct limits check)

**Problem.** `Share Posture` is one operator click per device and two ERS calls per device. At 50,000 endpoints that is neither usable nor kind to ISE. ISE pxGrid Direct can instead pull one flat JSON feed from an HTTPS URL with Basic auth (no pxGrid certificates needed), but it re-reads on a schedule, which looks automatic.

| Option | Description | Verdict |
|---|---|---|
| A | Feed contains only endpoints an operator explicitly shared | Keeps the rule, does not scale to 50k clicks |
| **B** | An admin approves a **sharing rule** (for example "share the status of all connected endpoints"). The rule is the human decision and is audited like any action | **Built (Option B)** |
| C | Publish everything automatically | Rejected: breaks the core rule |

**Amendment to the core rule.** "Operator-triggered" is widened to mean: a person triggers an action directly, or a person approves a standing sharing rule that is versioned, attributed and audited. Collecting a result still never shares it by itself. **Restrict and Clear stay per-device operator actions** and are never covered by a rule.

**What was built (feature X1):**
- `ise_sharing_rule` table (V24): versioned, one active at a time (`NONE`, `CONNECTED`, `CONNECTED_NON_COMPLIANT`), seeded with version 1 scope `NONE`. Changes are audited like policy edits. ADMIN only (`PUT /api/v1/sharing-rule`).
- `GET /api/v1/integrations/ise/endpoints`: read-only, flat JSON, scoped credential via `IseFeedAuthFilter` (HTTP Basic auth, `ROLE_ISE_FEED`, configurable via `app.ise-feed.username`/`password`, `ISE_FEED_USER`/`ISE_FEED_PASSWORD`, fails closed if blank) or `ADMIN` JWT. Serves only endpoints matching the active rule scope.
- Audit table `ise_feed_audit` (V24): logs timestamp, caller, rule version, and endpoint count on each fetch (payload itself is not stored).
- Services in package `com.endpointposture.isefeed`: `SharingRuleService`, `IseFeedService`, `IseFeedController`.
- Frontend `/ise-feed` page, `lib/apiExtra.ts`, and permission `editSharingRule` (`ADMIN`).

**Awaiting live verification:**
- Live test against real Cisco ISE pxGrid Direct remains UNVERIFIED.
- ISE pxGrid Direct ingestion limits (payload size, record count, pagination or incremental sync at 20k-50k devices) are not yet checked against real ISE. Also confirm the ISE version supports pxGrid Direct (3.1 or higher) and the license.

**Open sub-question:** will ISE policy combine this platform with another source (for example CrowdStrike)? If yes, the attribute schema must be stable and documented from day one.

## D4. Scale target and approach (Working default)

Personal project on one machine; hypothetical production target of 50,000 endpoints and about 20,000 live sessions each morning. Approach: simulator on this machine first, measure, then fix what bites. Kafka is not justified by job throughput (about 3 jobs per second at peak); site affinity can use the `shard` column in Postgres.

**Measured (pre-S12, 20,000 simulated sessions, 40 simulated workers):**

| Metric | Result |
|---|---|
| Watcher tick | 0.5 to 3 s upper bound (budget is the 15 s poll interval) |
| Dashboard summary | 20 to 60 ms |
| Dashboard trend (7 days) | 20 to 60 ms |
| Endpoints page (25 rows) | 25 to 500 ms |
| Job throughput | about 115 jobs/min (40 workers x about 3 jobs/min) |
| Hikari stalls | two, at 11:42 and 13:00 (housekeeper delta 1m39s and 1m6s) |

**Post-S12 re-run: PENDING.** S12 added connection keep-alive and lifetime tuning, `touch-min-minutes`, a per-sweep recheck cap, the fleet-list cap and trigram search indexes. Targets: no Hikari stall, endpoint search under 150 ms at 50,000 endpoints. See `scripts/sim/README.md`.

## D5. Enforcement mode (Open)

`ATTRIBUTE` is the default. Under it, Clear removes nothing in ISE. Switching to `ANC` needs the real ANC policy name confirmed in ISE (`Quarantine` is a guess).

## D6. Hardware and indicator thresholds (Working default)

Keep hardware bands 85/70/50 and the security-indicator thresholds as illustrative defaults, show them in the UI, and validate against real fleet data before relying on them.

## D7. Agent credential (Open)

One shared admin credential reaches every target. Decide on a least-privilege service account before any real deployment. The DPAPI file stays git-ignored and tied to the account that runs the backend.

## D8. Permanently out of scope

- Automatic enforcement of any kind.
- Browser-history collection (pending legal and HR sign-off).
- pxGrid proper (session event subscription) until the pxGrid persona and client certificates exist. This is different from pxGrid Direct in D3.
- Redis, Kafka, Kubernetes and the observability stack until a measured trigger appears (a second backend instance, real dispatch scale, a chosen deployment).

## D9. Login rate limiting behind a proxy (Working default)

The limiter is per client IP, in memory, one instance. The frontend proxies `/api/*` to the backend, so without help the backend sees one IP for everybody and all users share one bucket (default 10 logins a minute). `app.security.trust-forwarded-for` stays `false` by default. Set it to `true` only when the backend port is reachable solely through a proxy that overwrites `X-Forwarded-For`; never on a directly exposed backend, where clients could forge the header. Account lockout stays independent. Revisit if a second backend instance appears (the limiter would then need shared state, which is a measured trigger for Redis).

## D10. Retention of evidence (Decided: 90 days)

Evidence retention is decided at **90 days** (`app.retention.evidence-days: 90`, `audit-days: 90`). Evidence is NOT kept forever.

Implemented by `retention/EvidenceRetentionService` and `EvidenceRetentionScheduler` running nightly at 04:15 (`app.retention.evidence-cron: "0 15 4 * * *"`), using batched deletes (default 5000 rows per statement, up to 200 batches = 1,000,000 rows/table/run, each in its own transaction).

- **Pruned:** `assessment` (cascading to `check_result`), `hardware_health` (cascading to `hardware_recommendation`), `endpoint_diagnostic`, `endpoint_security_indicator`, `endpoint_inventory`, `endpoint_session_log`, `ise_action_audit`, and `ise_feed_audit`.
- **Protected:** Active pointers on `endpoint` (`latest_assessment_id`, `latest_hw_id`, `good_hw_id`) are never deleted. The newest diagnostic, security indicator, and inventory row per endpoint are always kept. The newest `RESTRICT` or `CLEAR_RESTRICTION` audit row per endpoint is always kept so `/ise/actions/state` remains accurate.
- **Partitioning (S11) deliberately not built:** S11 is marked **not needed at this window**. At 90 days (~11M assessment rows at the 20,000-device target), indexed batched deletes execute in milliseconds per batch without holding locks or generating long transactions, avoiding the need for composite foreign keys that table partitioning would require.
- Finished jobs continue to be pruned after 30 days (`JobRetentionScheduler`), and full inventory payloads are nulled after 10 runs per endpoint (`InventoryRetentionScheduler`).