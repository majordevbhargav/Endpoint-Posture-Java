# Page-by-page frontend redesign proposal

This review inspected the existing App Router, route pages, shared components, styling, API clients and response types. “Existing” below means source/API support found in the current repository, not a runtime verification. New or missing items are marked. See [`API-FUNCTIONALITY-MAP.md`](API-FUNCTIONALITY-MAP.md) for exact contract scope.

## Shared application shell

**Current:** `app/(dashboard)/layout.tsx` gates on `api.iseStatus()`; `Sidebar` has grouped Monitoring/Inventory/Operations navigation and a small backend/ISE status footer; `Topbar` provides endpoint quick search, theme control and account menu. The sidebar has fixed `w-64`; the header has fixed search width. ISE unavailability is also announced by `IseBanner`.

**Proposal:** Keep the product shell, simplify labels, retain clear endpoint search, place user actions in a predictable account menu, and show API/database/ISE states as separate named dependencies. Make the drawer button and global search keyboard-operable. A transient ISE outage must not be represented as an invalid login; distinguish 401 from dependency failure.

**Review:** Search results are clickable links but do not expose combobox/listbox semantics or a loading state. Sidebar status relies on dot/color alongside text; retain text and add freshness. Theme switch must announce current theme. Global shell should have skip link, landmarks, one page-level H1, and focus restoration.

## A. Main dashboard — `/overview`

**Existing:** total, connected, disconnected, compliant, noncompliant, error, unassessed and stale counts from `/api/v1/dashboard/summary`; compliance trend from `/trend`; category rates from `/categories`; recent endpoints and ISE audit activity. “Run posture checks” calls the existing bulk job route.

**Desktop 1440:** Page title and “updated at”/refresh row; 4 compact fleet KPIs; two-column body with compliance trend and status distribution, highest-priority endpoints, recent ISE actions, and a narrow service-health panel. Add OS distribution only if a future contract is approved. Worker block uses existing admin registry only for an ADMIN session; otherwise show a linkless “not available to this role” state or omit it.

**Tablet 768:** Collapse KPIs to 2 columns; trend and health stack; activity and endpoint lists become one-column sections; keep chart labels and summary counts visible.

**Mobile 390:** Single column, 2 KPI cards per row, concise time range and refresh controls, list summaries instead of dense chart/table. Prioritize “noncompliant/error” and freshness; hide no critical count.

**Problems addressed:** Current overview has valuable existing metrics, but some optional fetch failures are converted to empty values. Distinguish unavailable from zero. OS breakdown and worker delivery are not current dashboard contracts; label as unavailable/future, never use fabricated values.

## B. Endpoint management

### Inventory — `/endpoints`

**Existing:** server page `/api/v1/endpoints/page` with query, connectivity and posture status; current posture list fields; dashboard summary; page-level row expansion fetches latest posture/hardware; individual posture/hardware jobs; CSV export of loaded rows.

**Desktop 1440:** Page header with result count and refresh, search and filter toolbar, compact summary strip, then table columns for endpoint identity, IP/MAC, connection, posture status, last posture time, and row actions. Keep row selection and expand/detail action distinct. Preserve current actual filter parameters. Clearly label export as current loaded page/filtered data according to implementation semantics.

**Tablet 768:** Keep identity, posture state, connectivity and actions; move MAC/IP and freshness into secondary line/expand panel; filter controls wrap; paging remains visible.

**Mobile 390:** Replace table with endpoint cards: hostname/MAC, connected/last-known label, posture state and timestamp, clear “Open details” action. Search full width; filters in a bottom sheet/drawer with applied count; 44px targets.

**Problems addressed:** current table is dense, click-to-expand is not an accessible table interaction by default, and fixed minimum widths create horizontal scrolling. Ensure backend page totals are not confused with local pagination totals. No risk score field is verified on this response; do not invent one.

### Endpoint details — `/endpoints/[id]`

**Existing:** endpoint record; posture latest/history; hardware latest/history; diagnostics latest/history; security-indicator latest/history; session events; endpoint jobs; ISE action audit; actions for posture/hardware/diagnostic/security jobs and ISE share/restrict/clear.

**Desktop 1440:** Identity header with hostname, stable endpoint ID/MAC, connectivity freshness, posture status, and role-appropriate actions. Use tabs or anchored sections: Overview, Posture, Hardware, Software/Ports, Security Indicators, Jobs & Activity. Keep evidence timestamp and collection outcome adjacent to every result. Render possible indicator findings as review signals, not confirmed incidents.

**Tablet 768:** Header actions wrap below identity; tabs become scrollable with visible focus; evidence panels use two columns only when each remains readable.

**Mobile 390:** Header stacks; destructive/enforcement actions move to explicit overflow/action sheet with confirmation; use vertical evidence timeline and compact key-value blocks. No nested horizontal table.

**Problems addressed:** route performs many parallel calls and optional errors can be swallowed. Give each section independent loading/error/empty/stale states and show partial-page warning. Avoid collapsing “no report” and failed request into the same empty state. Software/ports are presently fleet inventory pages, not verified endpoint-detail APIs; do not imply endpoint-specific data unless wired to supported contracts.

## C. Job management — `/jobs`

**Existing:** queue counts from `/api/v1/system/health`; latest bounded job list `/api/v1/jobs?limit=...`; enqueue a single endpoint/job type; endpoint job list. Current `JobResponse` includes status, job type, priority, attemptCount/maxAttempts, error, created/start/completion times.

**Desktop 1440:** Queue summary for queued/running/complete/failed and oldest queue age; filters for known status/type and search only when API supports them; table with job/endpoint, type, lifecycle state, attempt count, created/start/completion, failure summary. Row opens detail panel with the fields currently available. Show clear scope e.g. “Latest 500 jobs” where that is the response. Do not show a fake percent-complete progress bar: the contract has no progress field.

**Tablet 768:** Drop low-priority columns into a details row; compact filter toolbar; keep state, endpoint and age visible.

**Mobile 390:** Job cards with type, endpoint, state, attempt count and important timestamp; detail expansion for error and timestamps; no auto-refresh toggle hidden in an inaccessible menu.

**Problems addressed:** current 4-second polling may be noisy; no server-side job paging/search, per-attempt history, worker owner, detailed execution output, or durable result-delivery state exists in the frontend contract. Label data as a recent bounded list. “Result delivery” must stay “not available” until supported.

## D. Worker management — proposed `/workers` view

**Existing support:** `GET /api/v1/admin/workers` exposes worker id/name/shard, enabled, `createdAt`, `lastSeenAt`, `revokedAt`, creator and derived ACTIVE/STALE/REVOKED. Enrollment-token creation and revoke are ADMIN-only. No frontend route currently exists.

**Desktop 1440:** Admin-only registry table with worker name/ID, shard, state text, last seen, created date and row actions. Header actions: “Create enrollment token” (existing API; reveal token once, warn copy/save) and refresh. Show exact stale threshold only if returned/documented. Separate a “Delivery telemetry unavailable” notice from actual heartbeat state.

**Tablet 768:** Keep name, state, last seen; put shard and dates in a detail expansion; token creation uses full-width dialog.

**Mobile 390:** Worker cards with name/state/last heartbeat and a detail view; token creation and revoke remain touch-sized, with confirmation. Never show an active job, last successful execution or outbox count as if available.

**Problems addressed:** current navigation omits existing worker administration, while system health reports local backend pool mode, not worker fleet. The actual admin endpoint is ADMIN protected; UI must mirror that rule and backend remains authoritative.

## E. Security indicators

**Existing:** endpoint detail exposes latest and history via `/api/v1/endpoints/{id}/security-indicators[/latest]`. `SecurityIndicatorsTab` shows status, risk level, findings, evidence map, sample counts, collection time and up to 15 recent records; messages correctly say short-sample findings are possible indicators and a missing scan is not a clean result.

**Proposal:** preserve that caveat prominently; make finding title, severity, endpoint link, collected time and evidence disclosure consistent. Do not label `FAILED`/`WINRM_UNAVAILABLE` as a clean state. Use text plus icon, never color alone.

**Responsive:** desktop table/list with filter only within the endpoint’s loaded history; tablet collapses evidence; mobile uses finding cards with evidence disclosure.

**Gap:** no fleet-wide indicator inventory, severity filter, endpoint association search, or historical cross-endpoint activity route was verified. A global Security Indicators page is future scope pending a contract; current detail tab is supported.

## F. Reports

**Existing:** CSV export utilities and table-level exports; dashboard trends; audit, posture, hardware and inventory read APIs. No generic report create/list/history/export API or scheduled reporting contract was verified.

**Proposal:** For now, describe exports by exact source and scope; include applied filters, export time, and row count where the current export can support them. A report center with saved queries, scheduled delivery, PDF, or historical snapshots is future functionality requiring a backend contract. Do not add those controls to the mockup as working actions.

**Responsive:** desktop filter bar and export scope; tablet grouped filters; mobile sequential filter sheet and explicit current-page/all-matching explanation.

## G. Settings and administration

### Users and roles — `/users`

Existing user list/create/role/enable/reset-password/delete API, ADMIN route guard, and frontend role visibility. Proposal: searchable user table; role/active state; confirm destructive actions; success/error feedback; hide the page/action from unauthorized roles. Pagination is not currently in the client contract.

### Policy and ISE sharing — `/policies`, `/ise-feed`

Existing application policy GET/history/PUT and ISE sharing-rule GET/history/PUT; feed preview GET is audited. Proposal: present active version, history/diff, validation and clear scope before save; confirm policy/rule changes. Do not imply these configure user authentication or system-wide runtime secrets.

### Authentication/system configuration

No editable authentication provider, MFA, password policy, secret rotation, or general system configuration UI/API was verified. Keep these future/operations-managed, not editable mockup forms. `/system` is health visibility, not a settings screen.

### Other existing pages to retain

- **Compliance `/compliance`:** endpoint page + posture latest batch + summary/category rate APIs. Keep status, checked time, filters and enqueue action. A fleet scan action needs clear scope/confirmation. Category filters and server-side sort need contract verification.
- **Hardware `/hardware`:** existing page/summary APIs and hardware check action. Keep score band, collection/failure time, recommendation count and details; preserve “no report” vs failed attempt.
- **Applications `/applications`, Ports `/ports`:** paged/filter/summary APIs exist. Keep installed-software policy context and port reachability explicit; avoid unbounded full-list behavior.
- **Warranty `/warranty`:** list and ADMIN CSV upload are present. Show row errors and saved count; avoid implying global report history.
- **Audit `/audit`:** current ISE action history from `/api/v1/audit/ise-actions`. Preserve actor, endpoint, outcome, detail and timestamp; add supported server filters only if the API accepts them. Current client uses a bounded list, so avoid “complete history” claims.
- **ISE actions `/ise-actions`:** current state from `/api/v1/ise/actions/state` plus `/api/v1/ise/status`; retain separation between latest action state and audit history. Keep polling age and ISE reachability distinct.
- **ISE feed `/ise-feed`:** sharing rule and paged preview exist; retain audit/freshness context and explicit scope.
- **System health `/system`:** `/api/v1/system/health` supplies UP/DEGRADED/DOWN, DB reachability, ISE, queue counts/age, and local backend worker-pool enabled/thread/mode. Keep each dependency visible and explain warnings. This is not a fleet-worker dashboard; do not use `REMOTE_PENDING` to infer endpoint worker connectivity.
- **Login `/login`:** current labeled username/password, visibility toggle, caps-lock notice, lock warning and inline error. Increase control sizes, ensure reveal-password button is keyboard reachable (it currently uses `tabIndex=-1`), and retain field values only when safe.
