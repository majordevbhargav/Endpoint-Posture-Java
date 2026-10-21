# Endpoint Posture and Compliance Platform
## Project Definition and Build Plan

Document version: 1.1
Prepared for: IT Security Engineering
Status: Draft for review, implementation not yet started

Revision note: Version 1.1 adds the Endpoint Hardware Health module (Section 6.1) after review of four additional prototype files, and updates the file inventory, data model, folder structure, roadmap, and open questions accordingly. All content from version 1.0 is unchanged except where noted.

---

## 1. Document Purpose

This document defines what the product is, what it replaces, what stays, what changes, and how the team will build it in stages. It is written so that a new engineer joining the project can read it once and understand the full system without needing to read every source file first. It also lists the specific decisions and inputs still needed from the project owner before implementation begins. Nothing in the build plan should start until Section 15 is answered.

---

## 2. Product Overview

### 2.1 What the product is

The product is an agentless endpoint posture and compliance visibility platform. It discovers endpoints connecting through Cisco ISE, runs posture checks against them without installing any permanent agent, stores the results, and presents them to an administrator through a web dashboard. The administrator decides what to do with that information. The product itself does not make network access decisions. Cisco ISE remains the single authority for network access and enforcement, using its own Authorization Policy.

### 2.2 What changed from the original design

The original build wrote posture results directly into ISE and immediately triggered enforcement, either a CoA re-authentication or an ANC quarantine action, the moment a check finished. That coupling is being removed.

The new design separates observation from enforcement:

- The platform observes and records posture. This is a fact: firewall state, open ports, installed applications, at the moment the check ran.
- ISE decides access. This is a policy decision, made by ISE's own Authorization Policy, using whatever signals ISE is given.
- A human decides whether and when to hand a posture fact to ISE, and whether and when to request a restriction.

This means "share posture with ISE" and "restrict this endpoint" become explicit, admin-triggered actions in the dashboard, not automatic side effects of a posture check.

### 2.3 Who uses it

A single primary user role for the first release: an IT security operator or administrator working from the web dashboard. Role-based access control for multiple operator types is listed as a later phase, not part of the first build (see Section 15, question 6).

---

## 3. Problem Statement

The existing prototype (the files already written and shared) proved the core mechanics work: session discovery, WinRM-based posture checks, a SQLite store, and a dashboard. Building the production version means fixing four structural problems found in that prototype before adding new capability on top of it.

**Problem 1: Enforcement is coupled to detection.**
`posture_app.py` currently calls `ise.write_posture()` and then either `enforce_attribute()` or `enforce_anc()` inside the same request that stores the assessment. There is no step where a human reviews the result first. This is the core architectural issue driving the pxGrid pivot and is addressed in Section 11.

**Problem 2: A device's stored status never expires.**
`endpoints` is updated with `INSERT ... ON CONFLICT(mac) DO UPDATE`, so the row always reflects the last check, however old. `ise_session_watcher.py` only re-queues a MAC for a fresh check once `RECHECK_INTERVAL_SECONDS` (four hours by default) has passed since it was last queued. It has no concept of a session ending. If a device disconnects and reconnects within that window, it is not re-checked, and the dashboard keeps showing the old result as current. The live compliance counts on the Dashboard page currently include every row in `endpoints`, connected or not, which means an endpoint that has been off the network for days is still counted as compliant. This is addressed in Section 8.4.

**Problem 3: Frontend and backend are not separated.**
The dashboard HTML, CSS, and JavaScript were originally embedded as a Python string inside `posture_ui.py`. This has already been pulled out into standalone files (`dashboard.html` and `console.html`), served from disk by Flask's `Response` object, but the surrounding project structure (static assets, templates directory, build tooling) has not been formalized yet. This is addressed in Section 9.

**Problem 4: Everything runs as a single-process, single-database prototype.**
Posture checks run one at a time, `pending_devices.txt` is a flat file with a Windows byte lock, and SQLite is a single-writer database. This is acceptable for a pilot of a few dozen endpoints. It is not acceptable at the scale referenced in the target architecture (tens of thousands of endpoints, thousands of concurrent sessions). This is addressed in Section 12 and Section 14.

---

## 4. Goals and Non-Goals

### 4.1 Goals

1. Discover endpoints connecting through ISE and run an agentless posture check against each one, on Windows first.
2. Store every posture result as a permanent, timestamped historical record. Never overwrite history, only append to it.
3. Show live, accurate compliance counts that reflect only endpoints currently connected to the network.
4. Let an administrator review a posture result and, on their own decision, share it with ISE and separately, request a restriction. Neither action happens automatically.
5. Keep ISE as the sole enforcement point. The platform never issues a CoA or ANC action without an explicit admin click.
6. Support both ISE integration methods, ERS REST (Context-In API) and pxGrid, behind one internal interface, selectable by configuration.
7. Provide a clean separation between frontend, backend, and database code, so each can be worked on and tested independently.
8. Provide an audit trail: every share, restrict, and refresh action is logged with who, what, and when.

### 4.2 Non-goals for the first release

- Automatic enforcement of any kind. This is intentionally removed, not deferred.
- macOS and Linux posture checks. The architecture allows for them (see the SSH worker path in the target architecture diagram), but the first release covers Windows only, matching the existing `posture_agent.ps1`.
- Multi-tenant or multi-organization support.
- A native mobile app. The dashboard is a responsive web application only.

---

## 5. High-Level Architecture

The platform has five stages, matching the target architecture diagram already produced for this project, with one change: the arrow from posture assessment into ISE is no longer automatic. It now passes through an admin review step.

**Stage 1, Discovery.** `ise_session_watcher.py` polls ISE's Session Directory (today through the MNT ActiveList API, with pxGrid Session Directory subscription available as a later swap, see Section 10) and identifies which MAC addresses currently have an active session, along with their IP address.

**Stage 2, Agentless assessment.** For each endpoint that is new or due for a recheck, `posture_agent.ps1` connects over WinRM or DCOM, with no agent installed on the endpoint, and runs the firewall, open-port, and application checks already implemented. It also now needs to answer one more question on every run: is this endpoint still an active ISE session at all, so a check is never queued against a device that has already left the network between being queued and being checked.

**Stage 3, Storage.** `posture_app.py` receives the result and calls `save_assessment()`. This step now stops here. It does not call `ise.write_posture()` or trigger `enforce_attribute()` or `enforce_anc()`. The result is simply recorded.

**Stage 4, Admin review.** The dashboard (`dashboard.html`, served through `posture_ui.py`) shows the current posture of every endpoint, along with whether it is presently connected. This is where the admin looks at the data.

**Stage 5, Admin action.** The admin can click "Share Posture with ISE," which writes the stored result into ISE as an endpoint custom attribute, the same operation `write_posture()` already performs today, just moved behind a button. The admin can separately click "Restrict Endpoint," which performs the same CoA or ANC action `enforce_attribute()` and `enforce_anc()` already perform today, also moved behind a button. These two actions are independent of each other. Sharing posture does not restrict a device, and restricting a device does not require having shared posture first.

ISE's own Authorization Policy is configured directly in ISE, outside this platform, to act on whatever attribute is shared. If nothing has been shared for a device, ISE simply does not have that signal and falls back to whatever else the policy evaluates.

---

## 6. System Components

This section maps every existing file to its role going forward. "Reuse as-is," "modify," and "retire" describe the intended treatment, subject to the answers in Section 15.

| File | Current role | Planned treatment |
|---|---|---|
| `ise_session_watcher.py` | Polls ISE MNT ActiveList, queues IPs for checking, tracks seen MACs | Modify: add session start/end tracking, remove the "seen forever" recheck gap described in Problem 2 |
| `posture_agent.ps1` | WinRM/CIM based posture collection, firewall, ports, applications, hardware, resource usage | Reuse as-is for the collection logic. No enforcement logic exists in this file today, so no removal needed here |
| `posture_app.py` | Receives posture results, saves them, writes to ISE, enforces via CoA or ANC | Modify: remove the automatic `ise.write_posture()` and enforcement call from `receive_posture()`. Add new endpoints for admin-triggered share and restrict actions |
| `posture_db.py` | SQLite schema and data access layer | Modify: add connection-state columns and a session log table, described in Section 7 |
| `posture_ui.py` | Flask app, dashboard API routes, queue worker | Modify: update the compliance summary query to filter by connection state, add routes for the new share/restrict/audit actions |
| `dashboard.html` | Main web dashboard, already separated from Python | Reuse and extend: add connection-state badges, share/restrict buttons, an Audit Logs page |
| `console.html` | Simpler queue/results console view | Reuse as-is, low priority for changes |
| `application_remediation.py` | Application classification and remote uninstall over WinRM | Reuse as-is. This module is unrelated to the ISE enforcement change and needs no modification |
| `endpoint_360_integration.py` | Endpoint experience and security indicator collectors, history storage | Reuse as-is |
| `endpoint_experience_dashboard.py` | Wi-Fi, gateway, DNS, application path scoring | Reuse as-is |
| `endpoint_security_indicators.py` | Lateral movement, beaconing, and external connection indicators | Reuse as-is |
| `endpoint_posture_dashboard.py` | Streamlit based demo dashboard with simulated data | Needs a decision, see Section 15, question 1 |
| `Save-PostureCredential.ps1`, `posture_common_cred.xml` | DPAPI-encrypted shared credential for WinRM | Reuse as-is |
| `ip_mac_map.txt`, `pending_devices.txt`, `seen_macs.txt` | Flat-file coordination between the watcher, the agent, and the UI | Replace with Redis in the phase described in Section 14. Kept as-is until that phase begins |
| `endpoint_hardware_warranty_collector.py` | Standalone PowerShell/CIM based collector for hardware identity, CPU, memory, storage health, battery, network adapters, hardware events, and warranty status, written to a JSON report | **Selected as the base for the new Endpoint Hardware Health module.** See Section 6.1 |
| `endpoint_compliance_hardware_health_dashboard.py` | Tkinter desktop demo, hardcoded/simulated hardware metrics for a single fictional endpoint, click-through detail cards | Not carried forward as running code. Its scoring concept (a 0 to 100 health score per component, color-banded) is reused conceptually in the new module, rebuilt against the existing Flask and `dashboard.html` stack rather than Tkinter, since a separate desktop app cannot share data or navigation with the web dashboard |
| `endpoint_productivity_browsing_dashboard.py` | Streamlit demo covering endpoint inventory, Chrome/Edge/Firefox browsing history collection, business/neutral/non-business classification, and a placeholder ISE quarantine flow | Its browser history collection functions (`read_chrome_edge_history`, `read_firefox_history`, `classify_domain`, `redact_url`) are real, working SQLite-based collectors and are sound. The page shell around them (Streamlit, demo endpoint table, placeholder quarantine) is not carried forward, for the same reasons as the two files above, plus the quarantine action it demonstrates is exactly the automatic enforcement pattern being removed per Section 2.2. This module is out of scope for the current build and is tracked separately, see Section 15, question 9 |
| `Endpoint_Hardware_Health_Status.docx`, `Browsing_History.docx` | Cover notes describing the two prototypes above | Reference only, no code, not carried forward |

---

### 6.1 Selecting the Endpoint Hardware Health source

Four files were reviewed for "which one becomes the endpoint status function": `endpoint_hardware_warranty_collector.py`, `endpoint_compliance_hardware_health_dashboard.py`, `endpoint_posture_dashboard.py` (already covered in Section 6), and `endpoint_productivity_browsing_dashboard.py`. Two of these are page shells around demo data (the Tkinter app and the Streamlit app) and neither collects anything from a real machine. Only `endpoint_hardware_warranty_collector.py` actually queries a live endpoint, using the same approach already proven in this project: PowerShell and CIM, no agent installed, matching `posture_agent.ps1` and `endpoint_experience_dashboard.py`. It is the one selected to build on.

What it already does well, and is kept unchanged:

- Identity collection through `Win32_ComputerSystem`, `Win32_ComputerSystemProduct`, and `Win32_BIOS`, giving manufacturer, model, serial number, and BIOS version.
- CPU load, memory usage, and physical disk health through `Get-PhysicalDisk` and the SMART failure-prediction namespace.
- Battery health, including design capacity versus full-charge capacity where the OEM exposes it.
- A rolling seven-day window of hardware-related Windows Event Log entries, filtered to the providers that actually matter (`WHEA`, disk, storport, Kernel-Power, USB), rather than the entire System log.
- A warranty lookup that checks a local CSV first and falls back to a clearly labeled, disabled-by-default Dell API integration point, rather than guessing or scraping an OEM website.
- A `proactive_recommendations()` function that turns storage health, battery wear, event volume, and warranty expiry into a short, prioritized action list.

What changes to fit this project:

- The script currently writes `endpoint_health_warranty_report.json` to local disk and exits. It needs the same shape `posture_agent.ps1` already uses: run against a target IP over the existing WinRM/CIM session pattern (remote, not only local), then POST its JSON report to a new endpoint on `posture_app.py` instead of writing a local file, so results land in the shared database rather than scattered across individual machines.
- The scoring idea from the Tkinter demo, a 0 to 100 score per component with green, yellow, orange, and red bands, is worth keeping as a concept, since it is a clear, glanceable way to present the CPU, memory, storage, and battery data this collector already gathers. It is implemented as a small scoring function inside the new integration module described below, not by pulling in the Tkinter file itself.
- Warranty data entry (the CSV format already defined in the collector's own docstring) needs a simple upload or manual-entry path in the dashboard, rather than assuming a CSV file sits next to the script on every machine that runs it.

New integration file, following the exact pattern `endpoint_360_integration.py` already established for wiring a standalone collector into the Flask app:

```
endpoint_hardware_health_integration.py
```

This module owns a new `endpoint_hardware_health` table (schema in Section 7.4), a `/api/endpoint-hardware-health/<mac>` read route, an `/api/v1/hardware-health` POST route that `posture_agent.ps1`, or a small new sibling script wrapping the warranty collector's PowerShell blocks, submits results to, and a Flask blueprint registered from `posture_ui.py` the same way `register_endpoint_360(app)` already is. The dashboard gains a new "Hardware Health" tab, sitting next to Endpoint 360 in the sidebar, built from the existing `.card`, `.grid-5`, and expandable-row patterns already used throughout `dashboard.html`, so it looks and behaves like the rest of the application rather than like a bolted-on separate tool.

---

## 7. Data Model

### 7.1 Current schema

The current schema in `posture_db.py` already separates concerns reasonably well: `endpoints` (current known state per MAC), `assessments` (append-only history), `check_results` (per-check detail per assessment), `endpoint_ports`, `endpoint_processes`, `endpoint_apps`, and `needs_attention`. This structure is kept.

### 7.2 New additions required for the connection-state fix

```sql
ALTER TABLE endpoints ADD COLUMN connected INTEGER DEFAULT 0;
ALTER TABLE endpoints ADD COLUMN session_started TEXT;
ALTER TABLE endpoints ADD COLUMN last_disconnected TEXT;

CREATE TABLE IF NOT EXISTS endpoint_session_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    mac TEXT NOT NULL,
    ip TEXT,
    event TEXT NOT NULL,
    timestamp TEXT NOT NULL
);
```

`connected` and posture status are independent facts. A device can be connected with a three-day-old posture result if it has not yet been rechecked since reconnecting. A device can also be disconnected with a perfectly good posture result from before it left. The Dashboard summary query only counts rows where `connected = 1`. History pages (Assessments, Audit Logs) are never filtered this way, since they show what happened, not what is true right now.

### 7.3 New additions required for admin-triggered ISE actions

```sql
ALTER TABLE endpoints ADD COLUMN shared_with_ise_at TEXT;
ALTER TABLE endpoints ADD COLUMN enforcement_state TEXT;

CREATE TABLE IF NOT EXISTS ise_action_audit (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp TEXT NOT NULL,
    mac TEXT NOT NULL,
    action TEXT NOT NULL,
    operator TEXT,
    result TEXT,
    detail TEXT
);
```

`action` is one of `SHARE_POSTURE`, `RESTRICT`, `CLEAR_RESTRICTION`. `operator` requires an authentication answer from Section 15, question 6, before it can be populated meaningfully.

### 7.4 New additions required for the Endpoint Hardware Health module

```sql
CREATE TABLE IF NOT EXISTS endpoint_hardware_health (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    mac TEXT NOT NULL,
    timestamp TEXT NOT NULL,
    manufacturer TEXT,
    model TEXT,
    serial_number TEXT,
    bios_version TEXT,
    cpu_score INTEGER,
    memory_score INTEGER,
    storage_score INTEGER,
    battery_score INTEGER,
    overall_score INTEGER,
    hardware_event_count INTEGER,
    warranty_status TEXT,
    warranty_days_remaining INTEGER,
    report_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_hardware_health_mac_time
    ON endpoint_hardware_health(mac, timestamp);

CREATE TABLE IF NOT EXISTS endpoint_hardware_recommendations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    mac TEXT NOT NULL,
    timestamp TEXT NOT NULL,
    priority TEXT NOT NULL,
    area TEXT NOT NULL,
    action TEXT NOT NULL
);
```

This follows the same pattern already used by `endpoint_360_integration.py` for its own history tables: one row per collection run, the full JSON report kept alongside a handful of indexed summary columns so the dashboard can query trends without parsing JSON on every page load. `report_json` stores the complete output of the hardware collector, unchanged, so nothing it already gathers is lost even before every field has a dedicated column.

---

## 8. Backend Plan

### 8.1 Removing automatic enforcement

In `posture_app.py`, inside `receive_posture()`, the block that currently calls `ise.write_posture(mac, ise_status, failed)` followed by either `ise.enforce_attribute(mac)` or `ise.enforce_anc(mac, compliant=...)` is removed from the automatic path. `save_posture()` remains, since storage is still automatic. The function returns a success response as soon as the result is stored, without touching ISE at all.

### 8.2 New endpoints

```
POST /api/v1/endpoints/<mac>/share-posture
POST /api/v1/endpoints/<mac>/restrict
POST /api/v1/endpoints/<mac>/clear-restriction
GET  /api/v1/endpoints/<mac>/ise-status
GET  /api/v1/audit/ise-actions
```

`share-posture` pulls the latest stored assessment for that MAC and calls the same ISE client logic `write_posture()` already contains today, then records the result in `ise_action_audit` and sets `shared_with_ise_at`. `restrict` and `clear-restriction` call the same logic `enforce_attribute()` and `enforce_anc()` already contain, again only on an explicit call, and set `enforcement_state` accordingly. Every one of these calls writes a row to `ise_action_audit` regardless of success or failure, so a failed CoA attempt is visible, not silently dropped.

### 8.3 ISE integration abstraction

A new module, `ise_transport.py`, defines a small interface with two methods, `publish_posture(mac, status, details)` and `publish_enforcement(mac, action, policy)`. Two implementations are built against it: `ers_transport.py`, wrapping the existing `ISEClient` class from `posture_app.py` almost unchanged, and `pxgrid_transport.py`, a new client built against ISE's pxGrid Endpoint Asset topic and Adaptive Network Control topic. Which one is active is controlled by an environment variable, `ENFORCEMENT_TRANSPORT`, set to either `ers` or `pxgrid`. The new share and restrict endpoints in Section 8.2 call whichever transport is configured, without knowing which one it is. This lets the team ship ERS first, since it is already working, and add pxGrid without touching any route logic.

### 8.4 Connection-state tracking

In `ise_session_watcher.py`, the end of the polling loop currently only logs when a MAC drops out of the active session list:

```python
for mac in set(seen) - current_macs:
    log.info("SESSION ENDED MAC=%s", mac)
```

This becomes an actual state change:

```python
for mac in set(seen) - current_macs:
    mark_disconnected(mac)
    seen.pop(mac, None)

for mac, fields in current.items():
    ip, _ = get_info(fields)
    mark_connected(mac, ip)
```

`mark_disconnected()` sets `connected = 0`, stamps `last_disconnected`, and writes a `DISCONNECTED` row to `endpoint_session_log`. Removing the MAC from `seen` is what fixes the four-hour recheck gap described in Problem 2, since `due_for_check()` already treats an unknown MAC as immediately due. `mark_connected()` sets `connected = 1` and, only if the endpoint was not already marked connected, stamps `session_started` and writes a `CONNECTED` row to the log.

In `posture_ui.py`, `/api/dashboard/summary` changes its endpoint loop to only tally rows where `connected = 1`. A new `not_connected` count is added to the response so the dashboard can show it as its own figure rather than silently dropping it.

---

## 9. Frontend Plan

### 9.1 Current state

`dashboard.html` and `console.html` are already standalone files served from disk through Flask's `Response(_read_dashboard_html(), mimetype="text/html")` pattern in `posture_ui.py`. This already solves the original complaint of frontend code living inside a Python string.

### 9.2 Formalizing the structure

```
project/
  backend/
    posture_app.py
    posture_ui.py
    posture_db.py
    ise_transport.py
    ers_transport.py
    pxgrid_transport.py
    application_remediation.py
    endpoint_360_integration.py
    endpoint_experience_dashboard.py
    endpoint_security_indicators.py
    endpoint_hardware_health_integration.py
    ise_session_watcher.py
  frontend/
    templates/
      dashboard.html
      console.html
    static/
      css/
      js/
      img/
  agents/
    posture_agent.ps1
    Save-PostureCredential.ps1
    hardware_health_agent.ps1
  db/
    migrations/
  docs/
    Endpoint_Posture_Platform_Project_Plan.md
```

Inline `<script>` and `<style>` blocks inside `dashboard.html` stay as they are for now, since the file is already large and functional. Splitting the JavaScript into separate static files under `frontend/static/js/` is listed as a later cleanup step, not required for correctness, and should only happen once the file is under version control with tests around the API it calls, so a refactor can be verified rather than trusted by eye.

### 9.3 New UI elements required

- A connection-state indicator (connected or disconnected) shown independently of the posture status badge, on both the Endpoints page and the Dashboard endpoint rows.
- A "Not Connected" stat card on the Dashboard, alongside the existing Compliant, Non-Compliant, and At Risk cards.
- "Share Posture with ISE" and "Restrict Endpoint" buttons on the expanded endpoint detail row, each behind a confirmation dialog, since both actions have real network consequences.
- A new Audit Logs page, reading from `ise_action_audit`, showing timestamp, MAC, action, operator, and result. The sidebar entry for this page already exists in `dashboard.html` as a placeholder and needs its "coming soon" content replaced with a real table.
- A new Hardware Health page, following Section 6.1, showing per-endpoint CPU, memory, storage, and battery scores as color-banded cards, hardware event trend, warranty status, and the prioritized recommendation list already produced by `proactive_recommendations()` in the source collector, reused unchanged.

---

## 10. ISE and pxGrid Integration

pxGrid is a transport, not a different concept from what the platform already does over ERS. The existing `ISEClient` class already performs the two operations that matter: writing posture as a custom attribute, and triggering CoA or ANC. pxGrid performs the same two operations by publishing onto ISE's Endpoint Asset topic and Adaptive Network Control topic instead of making a REST call.

Moving to pxGrid requires ISE's pxGrid persona to be enabled, and this platform registered as an approved pxGrid client using mutual TLS certificates, approved inside ISE's Admin console under pxGrid Services. This is a one-time setup step, separate from the existing `ISE_USER` and `ISE_PASS` basic auth credentials, and needs the ISE environment details listed in Section 15, question 3, before it can be configured.

Once both transports exist behind the `ise_transport.py` interface described in Section 8.3, switching between them is a configuration change, not a code change.

---

## 11. Compliance and Enforcement Model

Compliance, in this platform, is an observed fact about an endpoint at a point in time. It is stored, timestamped, and never silently overwritten to hide history, since every check produces a new row in `assessments`.

Enforcement is a decision made entirely inside Cisco ISE's Authorization Policy, using whatever attributes have been explicitly shared with it. The platform never decides network access on its own. The only two ways an endpoint's posture can affect its network access are:

1. An administrator clicks "Share Posture with ISE," after which ISE's own policy rules, configured in ISE, act on that attribute the next time the endpoint's session is evaluated.
2. An administrator clicks "Restrict Endpoint," which requests an immediate CoA re-authentication or ANC quarantine action, evaluated by ISE.

Neither action requires the other. An operator can share posture data for visibility purposes in ISE without ever restricting an endpoint, and can restrict an endpoint manually without having shared posture data first, for example in response to a security indicator finding from `endpoint_security_indicators.py` rather than a posture check result.

---

## 12. Technology Stack

### 12.1 Current stack, kept

- Python 3.11 or newer, Flask for the backend API and page serving.
- SQLite for the initial release, through `posture_db.py`.
- PowerShell, WinRM, and CIM for agentless Windows posture collection.
- Plain HTML, CSS, and JavaScript for the frontend, with Chart.js for charts.

### 12.2 Additions for this build

- `python-dotenv` or equivalent for managing `ENFORCEMENT_TRANSPORT`, ISE credentials, and pxGrid certificate paths outside of source control.
- `pytest` for testing the new share, restrict, and connection-state logic, particularly the dashboard summary query change described in Section 8.4, since a filtering bug there is exactly the kind of thing that is easy to get wrong silently.
- Black and Ruff for consistent formatting and linting across the growing codebase.

### 12.3 Additions for later phases, not required to start

- Redis, to replace the flat-file queue (`pending_devices.txt`) with proper list operations, removing the Windows byte-locking logic currently split across `posture_agent.ps1` and `posture_ui.py`.
- RQ or Celery, for a proper worker pool running posture checks concurrently instead of one at a time.
- PostgreSQL, once concurrent workers make SQLite's single-writer limitation a real bottleneck rather than a theoretical one.
- Gunicorn, to run the Flask app as multiple worker processes rather than the Flask development server.
- nginx, as a reverse proxy and load balancer once more than one Gunicorn instance is running.
- Docker Compose, to run Redis, PostgreSQL, and the application together in development with one command.

These are sequenced in Section 14 and should not be adopted before the specific problem each one solves is actually being felt, per the development guidance already given earlier in this project.

---

## 13. Security Considerations

- ISE credentials (`ISE_USER`, `ISE_PASS`) and the WinRM common credential (`posture_common_cred.xml`) must never be committed to version control. A `.gitignore` entry for `posture_common_cred.xml`, `posture.db`, and any `.env` file is required from the first commit.
- `posture_common_cred.xml` is encrypted with Windows DPAPI through `Export-Clixml`, decryptable only by the same Windows account on the same machine that created it. This property must be preserved. It should not be copied between machines or user accounts, and the existing warning logic in `posture_agent.ps1` and `application_remediation.py` around credential decryption failures should stay in place.
- Every share, restrict, and clear-restriction action must be recorded in `ise_action_audit`, including failed attempts, so the audit trail is complete rather than only reflecting successes.
- Once authentication is added to the dashboard (Section 15, question 6), every ISE action endpoint must record the authenticated operator, not just the action.
- Remote uninstall in `application_remediation.py` already restricts itself against a `PROTECTED_KEYWORDS` list. This protection is unrelated to the ISE enforcement change and should not be modified as part of this work.

---

## 14. Project Phases

**Phase 1, Decouple enforcement.** Remove the automatic `write_posture()` and `enforce_attribute()`/`enforce_anc()` calls from `receive_posture()`. Add the new share, restrict, and clear-restriction endpoints. Add the audit table and wire the dashboard buttons. This is the highest-priority phase and can be built entirely on the existing ERS transport, with no pxGrid work required yet.

**Phase 2, Fix connection-state tracking.** Add the schema changes from Section 7.2, update `ise_session_watcher.py` to mark connect and disconnect events, and update the dashboard summary query to filter by `connected = 1`. Add the Not Connected stat card and per-row connection badges.

**Phase 3, Formalize frontend structure and finish the Audit Logs page.** Move files into the folder structure in Section 9.2, replace the Audit Logs placeholder in `dashboard.html` with a real table reading from `ise_action_audit`.

**Phase 3a, Endpoint Hardware Health module.** Adapt `endpoint_hardware_warranty_collector.py` into `hardware_health_agent.ps1` for remote execution over the existing WinRM/CIM pattern, build `endpoint_hardware_health_integration.py` following Section 6.1, add the schema from Section 7.4, and add the Hardware Health page to `dashboard.html`. This phase has no dependency on Phase 2 or the ISE transport work and can be built in parallel with either.

**Phase 4, Introduce the ISE transport abstraction.** Build `ise_transport.py` and `ers_transport.py` as a refactor of the existing `ISEClient`, with behavior unchanged, verified with tests before anything else depends on it.

**Phase 5, Add pxGrid transport.** Requires the environment details in Section 15, question 3. Build `pxgrid_transport.py` against the same interface, test it against a non-production ISE instance before enabling it against production.

**Phase 6, Concurrency and queue rework.** Replace `pending_devices.txt` with Redis, introduce a worker pool for posture checks, following the tiered learning plan already agreed for this project.

**Phase 7, Production hardening.** Migrate from SQLite to PostgreSQL, move from the Flask development server to Gunicorn, add nginx in front of multiple application instances, containerize the full stack with Docker Compose.

Each phase should be a separate set of commits with its own tests, and no phase should begin before the previous one is verified working, since Phase 1 alone is already a meaningful production-safety change on its own and should not wait on the later phases to ship.

---

## 15. Information Needed Before Implementation Begins

The following must be answered before Phase 1 starts, since they affect the shape of the code being written, not just later phases.

1. **Streamlit dashboard (`endpoint_posture_dashboard.py`).** Is this file still in use anywhere, or was it an earlier prototype that has been fully superseded by `dashboard.html` and `posture_ui.py`. If it is retired, it should be removed from the repository rather than left as dead code that looks like a second, conflicting frontend.

2. **ERS versus pxGrid for the first release.** Should Phase 1 ship using the existing ERS transport only, with pxGrid deferred to Phase 5 as written above, or is pxGrid required from day one. This affects whether Phase 1 can start immediately or needs to wait on ISE pxGrid persona setup.

3. **ISE environment details.** ISE version and patch level, whether pxGrid persona is already enabled on the deployment, and who owns creating and approving the pxGrid client certificate for this platform inside ISE Admin. Also confirm the ISE host, port, and whether TLS certificate verification (`ISE_VERIFY_TLS`) should be enabled in the target environment, since it is currently defaulted to false.

4. **Scale target for the first release.** The architecture diagram references 40,000 endpoints and roughly 20,000 concurrent sessions. Is that the actual target for the first production release, or is that the long-term target with a smaller pilot group first. This determines whether Phase 6 and Phase 7 need to happen before go-live or can genuinely wait.

5. **Hosting environment.** Where will this run: on-premises Windows Server, a Linux VM, a cloud environment. `posture_agent.ps1` requires PowerShell and WinRM connectivity to targets, so the machine running the scheduled checks needs network line-of-sight to every endpoint being assessed, on the ports WinRM uses.

6. **Authentication and roles for the dashboard.** The current dashboard has no login. Before ISE actions can be attributed to an operator in the audit log as described in Section 13, a decision is needed on how operators authenticate: local accounts, SSO against an existing identity provider, or something else, and whether a single administrator role is sufficient for the first release or multiple roles are required immediately.

7. **CoA and ANC policy names.** `enforce_anc()` currently applies a policy named `"Quarantine"` by name. Confirm this matches the actual ANC policy name configured in the target ISE deployment, and confirm what the correct behavior should be if that policy does not exist, since a typo here today would fail silently as an HTTP error rather than a clear message to the operator.

8. **Recheck interval and disconnect handling for edge cases.** Section 8.4 marks a device disconnected the moment it drops out of ISE's active session list on a single poll. Confirm whether a brief network blip should immediately mark a device disconnected, or whether a short grace period, for example missing two consecutive polls, is preferred to avoid flapping the connection-state indicator during normal roaming or short outages.

9. **Endpoint Productivity and Browsing Visibility module.** `endpoint_productivity_browsing_dashboard.py` was reviewed as part of this round of files and contains working browser history collectors, but browsing history is materially more sensitive than firewall or application inventory data. Before this module is added to the roadmap at all, confirm whether it is in scope for this project, and if so, whether the organization already has the legal and HR sign-off, employee notice process, and retention policy referenced in the file's own privacy notice. This platform should not collect browsing data ahead of that confirmation.

10. **Warranty data source.** `endpoint_hardware_warranty_collector.py` supports a local CSV lookup and a disabled-by-default Dell API integration point. Confirm whether warranty data will come from a CSV export from the existing asset/ITAM system, a live OEM API (Dell, HP, Lenovo, whichever vendors are in the fleet), or is out of scope for the first release of the Hardware Health module, with only the CPU, memory, storage, and battery health scores shipping initially.

11. **Hardware health score thresholds.** The score bands sketched in Section 6.1 (green, yellow, orange, red) need actual threshold values before the scoring function can be written. Confirm whether the illustrative bands already used in the Tkinter demo (85 and above healthy, 70 to 84 warning, 50 to 69 degraded, below 50 critical) are acceptable as a starting point, or whether the organization has its own thresholds for storage wear, battery health, and CPU temperature.

Implementation on Phase 1 can begin as soon as questions 1, 2, 6, and 7 are answered, since those are the ones that directly shape the code being written in that phase. Questions 3, 4, 5, and 8 affect later phases and can be answered in parallel while Phase 1 is underway. Phase 3a can begin as soon as questions 10 and 11 are answered, and does not need to wait on questions 1 through 8. Question 9 gates whether Phase 3a's sibling browsing module is ever scheduled at all.

---

## 16. Appendix, File Inventory Reused From the Existing Project

- `ise_session_watcher.py`
- `posture_agent.ps1`
- `posture_app.py`
- `posture_db.py`
- `posture_ui.py`
- `dashboard.html`
- `console.html`
- `application_remediation.py`
- `endpoint_360_integration.py`
- `endpoint_experience_dashboard.py`
- `endpoint_security_indicators.py`
- `endpoint_posture_dashboard.py` (pending decision, Section 15 question 1)
- `Save-PostureCredential.ps1`
- `posture_common_cred.xml`
- `ip_mac_map.txt`
- `pending_devices.txt`
- `seen_macs.txt`
- `posture_ui_state.json`
- `endpoint_hardware_warranty_collector.py` (selected as the base for the new Hardware Health module, Section 6.1)

Reviewed but not carried forward as running code, kept here for traceability:

- `endpoint_compliance_hardware_health_dashboard.py` (Tkinter demo, concept reused, code not reused)
- `endpoint_productivity_browsing_dashboard.py` (Streamlit demo, browser history functions are sound but the module is out of scope for the current build, see Section 15, question 9)
- `Endpoint_Hardware_Health_Status.docx`, `Browsing_History.docx` (cover notes for the two files above, no code)

No source file listed above is being discarded without a stated reason. Every modification described in this document is a targeted change to existing, working code, not a rewrite from scratch.
