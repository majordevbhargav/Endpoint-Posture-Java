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

## D3. ISE sharing model at scale (Working default: option B, not built)

**Problem.** `Share Posture` is one operator click per device and two ERS calls per device. At 50,000 endpoints that is neither usable nor kind to ISE. ISE pxGrid Direct can instead pull one flat JSON feed from an HTTPS URL with Basic auth (no pxGrid certificates needed), but it re-reads on a schedule, which looks automatic.

| Option | Description | Verdict |
|---|---|---|
| A | Feed contains only endpoints an operator explicitly shared | Keeps the rule, does not scale to 50k clicks |
| **B** | An admin approves a **sharing rule** (for example "share the status of all connected endpoints"). The rule is the human decision and is audited like any action | **Chosen default** |
| C | Publish everything automatically | Rejected: breaks the core rule |

**Amendment to the core rule (needs your explicit sign-off).** "Operator-triggered" is widened to mean: a person triggers an action directly, or a person approves a standing sharing rule that is versioned, attributed and audited. Collecting a result still never shares it by itself. **Restrict and Clear stay per-device operator actions** and are never covered by a rule.

**Design (not built):**
- `ise_sharing_rule` table: versioned, one active at a time, `created_by`, `created_at`, scope (initially: all connected endpoints, or by posture status), changes audited like policy edits. ADMIN only.
- `GET /api/v1/integrations/ise/endpoints`: read-only, flat JSON, scoped credential (own filter and key, like `PostureApiKeyFilter`; ISE never receives a user JWT), serves only endpoints the active rule covers.
- Fields: MAC (colon format), posture status, failed checks, last checked, connected, hardware band, security risk level. Names go in a dedicated ISE dictionary (for example `VEPosture:*`).
- A `PosturePublisher` interface with `ErsPublisher` (today's per-device path) and `PxGridDirectPublisher` (the feed), mirroring `IseTransport`.
- Every rule change and every feed fetch is audited (counts, not full payloads).

**Check before building:** ISE's pxGrid Direct limits (payload size, record count, pagination or incremental sync). At 50k endpoints one file may be tens of MB, and this is unverified. Also confirm the ISE version supports pxGrid Direct (3.1 or higher) and the licence.

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

## D10. Retention of evidence (Open)

Assessments, check results, hardware, diagnostics, indicator rows and audit rows are kept forever by design. Finished jobs are pruned after 30 days and inventory payloads beyond the newest 10 runs per endpoint. Decide how long "evidence forever" really is before building partitioning (S11); at the target scale it is about 120k assessments a day.