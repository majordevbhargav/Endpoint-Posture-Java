# Endpoint Posture & Compliance Platform

An **agentless endpoint visibility, posture and compliance platform** that works alongside **Cisco ISE**.

It discovers active endpoints through Cisco ISE, remotely collects Windows posture, hardware, network-experience and connection-indicator data, stores the history in PostgreSQL, and gives an operator a dashboard to investigate and take controlled ISE actions.

> **Observe → Collect → Store → Review → Act**

Cisco ISE stays the network-enforcement authority. The platform never turns a collected result into an ISE action by itself. See `DECISIONS.md` (D3) for how bulk sharing fits that rule.

---

## What it does

* **Endpoint discovery**: polls ISE sessions, tracks MAC/IP and connect/disconnect (with a grace period against flapping), keeps a session history per device.
* **Posture**: Windows Firewall, listening ports with a reachability probe, installed applications judged against a versioned required/blocked policy, processes, resources, OS.
* **Hardware health**: CPU, memory, storage, battery, BIOS, 7-day hardware events, recommendations, warranty (from an uploaded CSV), trend charts.
* **Endpoint 360** (on demand): network diagnostics run on the endpoint (gateway, DNS, TCP 443, traceroute) with a transparent score, and connection-based security indicators (possible lateral movement, beaconing). Indicators are evidence for a person to review and never trigger enforcement.
* **ISE actions**: Share Posture, Restrict, Clear, each a separate operator action with its own role requirement and its own audit row, success or failure. A fleet page shows the latest enforcement state per device.
* **Automation**: worker pool, automatic rechecks (posture every 4 h, hardware every 24 h), stale-job recovery, inventory retention.
* **Access control**: roles `ADMIN`, `OPERATOR`, `ANALYST`, `VIEWER` enforced on the server; user management; login lockout.
* **Operations**: System Health page, Prometheus metrics (`/actuator/prometheus`, admin only), CI, frontend container image.

## Architecture

```text
              Cisco ISE
      MNT session list / ERS actions
                 │
                 ▼
   Spring Boot API  ◄──── HTTP/JSON + agent key ────  PowerShell agents
   (jobs, ingest,                                      (CIM / WinRM / DCOM)
    ISE actions)                                              │
        │                                                     ▼
        ▼                                             Windows endpoints
   PostgreSQL 16
        │
        ▼
   Next.js dashboard
```

Observation (ingest) and enforcement (ISE actions) live in separate controllers and services, so recording a result can never fall through into an ISE call.

## Technology

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot 3.5.16, Maven, Spring Security + JWT, Flyway |
| Database | PostgreSQL 16 (migrations V1 to V17; V5 to V7 intentionally absent) |
| Collection | PowerShell 5+, Windows CIM / WinRM / DCOM |
| Network | Cisco ISE MNT (sessions) and ERS (actions) |
| Frontend | Next.js 16, React 19, TypeScript, Tailwind CSS 3 |
| Delivery | Docker Compose (Postgres, Adminer, frontend), GitHub Actions CI |

## Run it

```powershell
docker compose up -d                       # Postgres + Adminer
.\scripts\Save-PostureCredential.ps1       # one time, same Windows account that runs the backend
cd backend ; .\start-dev.ps1               # API :8090, Swagger at /swagger-ui.html
cd ..\frontend ; npm install ; npm run dev # http://localhost:3000
```

Details, configuration and troubleshooting: `PROJECT_COMPLETE_GUIDE.md` and `HANDOFF.md`.

## Documentation map

| File | Purpose |
|---|---|
| `PROJECT_COMPLETE_GUIDE.md` | Full reference: flows, database, packages, API, config, agents, frontend, security. The authority when documents disagree. |
| `DECISIONS.md` | Decisions taken and open: scope, deployment, ISE sharing, scale. |
| `FEATURE_ROADMAP.md` | What is done and what is left, in order. |
| `ROADMAP.md` | System at completion, scale analysis, infrastructure phases. |
| `HANDOFF.md` | Start here when picking the project up: run, verify, known traps. |

## Status

Backend, agents and dashboard are built through Endpoint 360, warranty, lockout, metrics and the frontend image. Left: scale hardening and measurement, the ISE feed, production hardening and deployment. Application remediation is **out of scope**. See `FEATURE_ROADMAP.md`.

**The platform provides visibility and intelligence. Cisco ISE remains the enforcement layer.**