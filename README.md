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
  * Detect endpoint connect/disconnect events

* 🛡️ **Endpoint Posture**

  * Windows Firewall status
  * Listening ports
  * Installed applications
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
  * Application, port and process inventory
  * Session history
  * Endpoint hardware history

* 🔐 **Cisco ISE Integration**

  * Cisco ISE session discovery
  * ERS-based communication
  * Posture sharing with ISE
  * Controlled restriction and restriction clearing
  * Administrative action auditing

* 🧑‍💻 **Operator Dashboard**

  * Endpoint investigation
  * Compliance status
  * Hardware health
  * Historical information
  * Remediation controls

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
| PostgreSQL            | Persistent source of truth and historical evidence |
| ISE Transport         | Abstracts Cisco ISE communication                  |
| Dashboard             | Endpoint investigation and operator control        |

---

## Data Model

The platform maintains more than a simple compliant/non-compliant flag.

```text
endpoints
    │
    ├── assessments
    │      └── check_results
    │
    ├── endpoint_apps
    ├── endpoint_ports
    ├── endpoint_processes
    │
    ├── endpoint_session_log
    │
    ├── endpoint_hardware_health
    ├── endpoint_hardware_recommendations
    │
    └── ise_action_audit
```

This allows the system to maintain both **current endpoint state and historical evidence**.

---

## Posture Checks

Current posture collection includes areas such as:

```text
Windows Firewall
Listening Ports
Installed Applications
Processes
System Resources
Operating System
Endpoint Identity
```

The Windows agent can perform remote endpoint inspection and submit the resulting JSON report to the backend posture API.

---

## Hardware Health

Hardware telemetry includes:

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

Hardware health is treated as a separate subsystem and can be periodically refreshed by the platform.

---

## Cisco ISE Actions

The platform keeps posture sharing and enforcement as separate operations.

```text
Share Posture
      ≠
Restrict Endpoint
      ≠
Clear Restriction
```

Posture information can be published to ISE, while restriction actions can be explicitly requested by an administrator through the supported enforcement mechanisms.

---

## Technology

**Backend**

* Java
* Spring Boot
* Maven
* REST APIs
* Spring Security / JWT

**Database**

* PostgreSQL

**Endpoint Collection**

* PowerShell
* Windows CIM / WinRM / DCOM

**Network / Security**

* Cisco ISE
* ERS REST API
* CoA / ANC integration

**Frontend**

* HTML
* CSS
* JavaScript
* Chart.js

---

## Design Principles

### 1. Observation ≠ Enforcement

The platform collects and evaluates endpoint evidence. Cisco ISE remains responsible for network enforcement.

### 2. Current State ≠ Historical State

An endpoint's latest posture is separate from its connection state and historical assessments.

For example:

```text
Connected + Compliant
Connected + Non-Compliant
Disconnected + Previously Compliant
Disconnected + Previously Non-Compliant
```

Connection history is maintained independently.

### 3. Evidence First

Instead of storing only:

```text
COMPLIANT
```

the platform maintains the underlying checks, inventory, assessments and history.

### 4. Human-Controlled Enforcement

The system does not silently quarantine or restrict an endpoint because a posture check failed.

---

## Project Status

🚧 **Active Development**

The platform is being rebuilt around a cleaner backend architecture and centralized PostgreSQL persistence.

Planned areas include:

* Stronger RBAC
* Production-scale endpoint processing
* Expanded endpoint intelligence
* Hardware health improvements
* Frontend modernization
* Production deployment architecture

Some of these areas remain intentionally open design decisions.

---

## Project Goal

The long-term goal is to provide a centralized endpoint intelligence and compliance control plane for Cisco ISE-managed enterprise networks.

```text
Cisco ISE
   │
   │ Who is connected?
   ▼
Endpoint Intelligence
   │
   │ What is happening?
   ▼
Posture + Hardware + Evidence
   │
   ▼
PostgreSQL
   │
   ▼
Administrator Review
   │
   ├── Share posture
   ├── Restrict
   └── Clear restriction
   │
   ▼
Cisco ISE
```

**The platform provides the visibility and intelligence. Cisco ISE remains the enforcement layer.**
