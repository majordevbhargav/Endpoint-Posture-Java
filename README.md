# Endpoint Posture & Compliance Platform

An **agentless endpoint visibility, posture, and compliance platform** designed to work alongside **Cisco ISE**.

The platform discovers active endpoints through Cisco ISE, remotely collects Windows endpoint posture and hardware information, stores historical evidence in PostgreSQL, and provides an operator dashboard for investigation and controlled remediation.

> **Observe → Collect → Store → Review → Act**

Cisco ISE remains the network enforcement authority. The platform does **not** automatically turn a posture result into a network-access decision.

---

## What It Does

* 🔍 **Endpoint Discovery**

  * Discover active endpoints through Cisco ISE
  * Track MAC/IP and connection state
  * Detect endpoint connect/disconnect events (with a grace period against flapping)

* 🛡️ **Endpoint Posture**

  * Windows Firewall status
  * Listening ports and reachability
  * Installed applications, judged against a versioned required/blocked policy
  * Processes and resources
  * OS and endpoint information

* 💻 **Hardware Health**

  * CPU and memory utilization
  * Storage health
  * Battery health
  * BIOS and hardware information
  * Windows hardware events
  * Health recommendations

* 📊 **Endpoint Visibility**

  * Current endpoint state
  * Historical assessments
  * Application and port inventory
  * Session history
  * Endpoint hardware history
  * Automatic rechecks (posture every 4 h, hardware every 24 h)

* 🔐 **Cisco ISE Integration**

  * Cisco ISE session discovery
  * ERS-based communication
  * Posture sharing with ISE
  * Controlled restriction and restriction clearing
  * Administrative action auditing

* 🧑‍💻 **Operator Dashboard and Access Control**

  * Endpoint investigation, compliance matrix, hardware telemetry
  * Application policy editor with version history
  * System health page
  * Roles: `ADMIN`, `OPERATOR`, `ANALYST`, `VIEWER`, enforced on the server
  * User management (admin only)

---

## Architecture

```text
                 ┌──────────────────┐
                 │    Cisco ISE     │
                 │                  │
                 │ Session Discovery│
                 │ ERS / Enforcement│
                 └────────┬─────────┘
                          │
                          ▼
                 ┌──────────────────┐
                 │ Endpoint         │
                 │ Discovery        │
                 └────────┬─────────┘
                          │
                          ▼
                 ┌──────────────────┐
                 │ Windows Endpoint │
                 │                  │
                 │ Posture          │
                 │ Hardware Health  │
                 └────────┬─────────┘
                          │
                       HTTP/JSON
                          │
                          ▼
                 ┌──────────────────┐
                 │ Backend API      │
                 │                  │
                 │ Validation       │
                 │ Processing       │
                 │ ISE Integration  │
                 └────────┬─────────┘
                          │
                          ▼
                 ┌──────────────────┐
                 │   PostgreSQL     │
                 │                  │
                 │ Current State    │
                 │ History          │
                 │ Inventory        │
                 │ Audit Logs       │
                 └────────┬─────────┘
                          │
                          ▼
                 ┌──────────────────┐
                 │ Admin Dashboard  │
                 │                  │
                 │ Investigate      │
                 │ Review           │
                 │ Share / Restrict │
                 └──────────────────┘
```

The architecture deliberately separates **observation from enforcement**. Endpoint agents collect evidence, PostgreSQL maintains the history, and the administrator decides whether an ISE action should occur.

---

## Core Components

| Component             | Purpose                                            |
| --------------------- | -------------------------------------------------- |
| Cisco ISE             | Endpoint/session discovery and network enforcement |
| Windows Posture Agent | Collects endpoint security posture                 |
| Hardware Health Agent | Collects hardware telemetry                        |
| Backend API           | Receives, validates and processes endpoint data    |
| Job queue             | PostgreSQL-backed queue with a worker pool         |
| PostgreSQL            | Persistent source of truth and historical evidence |
| ISE Transport         | Abstracts Cisco ISE communication                  |
| Dashboard             | Endpoint investigation and operator control        |

---

## Data Model

These are the tables created by the Flyway migrations `V1` to `V13`.
Version numbers jump from V4 to V8; the gap is harmless.

```text
app_user                          (V1)   login users and roles

endpoint                          (V2)   one row per device, keyed by MAC
    │
    ├── posture_job               (V3)   the work queue
    │
    ├── assessment                (V4)   append-only posture history
    │      └── check_result      (V4)   one row per check, details as JSONB
    │
    ├── endpoint_inventory        (V12)  per-run apps, ports, processes (JSONB)
    │
    ├── hardware_health           (V8, V9)  per-run scores and raw report
    │      └── hardware_recommendation (V8)
    │
    ├── endpoint_session_log      (V10)  connect / disconnect events
    │
    └── ise_action_audit          (V11)  every Share / Restrict / Clear attempt

app_policy                        (V13)  versioned application policy
    └── app_policy_rule           (V13)  required / blocked patterns
```

Assessments, check results, hardware reports, inventory, session log and audit rows are append-only. Only `endpoint`, `posture_job`, `app_user` and `app_policy.active` change in place.

---

## Posture Checks

```text
Windows Firewall
Listening Ports (with reachability probe)
Installed Applications (required / blocked policy)
Processes
System Resources
Operating System
Endpoint Identity
```

The Windows agent performs remote inspection and submits a JSON report to the backend posture API. It authenticates with a shared key, which only opens the two ingestion routes.

---

## Hardware Health

```text
CPU
Memory
Storage
Battery
BIOS
Manufacturer / Model / Serial
Windows Hardware Events
Recommendations
```

A failed collection leaves a permanent `succeeded = false` row. The dashboard keeps showing the last good scores with a warning.

---

## Cisco ISE Actions

```text
Share Posture
      ≠
Restrict Endpoint
      ≠
Clear Restriction
```

Each is a separate operator action with its own role requirement and its own audit row, whether it succeeds or fails.

---

## Technology

**Backend**

* Java 21
* Spring Boot 3.5
* Maven
* REST APIs
* Spring Security / JWT / role-based access

**Database**

* PostgreSQL 16, Flyway migrations

**Endpoint Collection**

* PowerShell
* Windows CIM / WinRM / DCOM

**Network / Security**

* Cisco ISE
* ERS REST API
* CoA / ANC integration

**Frontend**

* Next.js 16
* React 19
* TypeScript
* Tailwind CSS

---

## Design Principles

### 1. Observation ≠ Enforcement

The platform collects and evaluates endpoint evidence. Cisco ISE remains responsible for network enforcement.

### 2. Current State ≠ Historical State

An endpoint's latest posture is separate from its connection state and historical assessments.

```text
Connected + Compliant
Connected + Non-Compliant
Disconnected + Previously Compliant
Disconnected + Previously Non-Compliant
```

### 3. Evidence First

Instead of storing only `COMPLIANT`, the platform keeps the underlying checks, inventory, assessments and history.

### 4. Human-Controlled Enforcement

The system does not silently quarantine or restrict an endpoint because a posture check failed.

---

## Project Status

🚧 **Active Development**

Built: security and roles, endpoint discovery, job queue with worker pool and automatic rechecks, posture, hardware health, ISE actions and audit, application policy, dashboard APIs, system health, and the Next.js dashboard.

Still planned: login lockout, metrics, containerization, warranty data, Endpoint 360 diagnostics, and application remediation. See `FEATURE_ROADMAP.md` for the ordered list.

---

## Project Goal

The long-term goal is to provide a centralized endpoint intelligence and compliance control plane for Cisco ISE-managed enterprise networks.

**The platform provides the visibility and intelligence. Cisco ISE remains the enforcement layer.**