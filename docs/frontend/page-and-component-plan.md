# Frontend page and component plan

Status: proposed plan based on the current routes, components, and verified backend contracts. No endpoints are invented. A missing contract is marked explicitly. The plan uses the proposed product system in [`DESIGN.md`](DESIGN.md).

## Existing pages to refine

| Page | Current route and actual contract | Proposed page structure / components | Contract gaps |
|---|---|---|---|
| Overview | `/overview`; `GET /api/v1/dashboard/summary`, `/trend`, `/categories`, endpoint `/page`, audit `/ise-actions`. | Fleet status summary, compliance trend, highest-risk endpoints, recent action evidence, clear freshness and partial-data labels. Use `MetricCard`, accessible chart wrapper, endpoint `DataTable`, stale/error states. | Summary is present but does not include worker fleet freshness or result delivery. |
| Endpoint inventory | `/endpoints`; `GET /api/v1/endpoints/page`, `/api/v1/posture/latest/batch`, dashboard summary; POST job bulk/single. | Server-filtered fleet table, query/filter bar, status/freshness columns, selection and job action; persistent filter URL. | Confirm API supports required multi-filter/sort and stable cursor behavior. Do not fetch all 50k rows. |
| Endpoint detail | `/endpoints/[id]`; endpoint GET plus posture/hardware/diagnostic/security/session history, endpoint jobs and ISE audit routes. | Summary header, identity and last-seen, posture timeline, hardware, diagnostics, security indicators, job and audit timeline. Use tabs or progressive sections with per-section loading/error states. | Verify endpoint DTO provides connectivity/worker attribution and explicit freshness; no single aggregated details contract exists. |
| Compliance | `/compliance`; endpoints page, latest posture batch, dashboard summary/categories, bulk posture job. | Fleet compliance table, filter by status/category, trend, recency and evidence detail link. | Confirm category-level paged compliance query/sorting; current page appears to derive posture for a page of endpoints. |
| Hardware | `/hardware`; `/api/v1/hardware-health/page`, `/summary`, endpoint latest hardware and jobs. | Hardware health summary and server-paged table with band, age, component details. | Verify filters and per-endpoint current pointer semantics at target scale. |
| Jobs | `/jobs`; `GET /api/v1/jobs?limit=...`, endpoint jobs, `/api/v1/system/health`; enqueue routes. | Queue overview, active/failed/recent tabs, state/age/attempt, endpoint link, details drawer with lifecycle and failure. Expose polling pause and last refresh time. | Missing server-paged searchable job-history contract; `listJobs(500)` is bounded. No worker delivery/outbox status contract. |
| System health | `/system`; `GET /api/v1/system/health`, ISE `/status`. | Separate application dependencies (API/DB/ISE), local scheduler and queue, and link to worker fleet. Keep unknown distinct from healthy. | Current health reports backend local pool mode/threads, not worker fleet. Do not merge these states. |
| Applications / ports | `/applications`, `/ports`; paged and summary APIs plus full list APIs. | Searchable server-paged tables, summary, freshness per collection, filters and export scope. | Confirm list endpoints are not used for large fleets; require bounded paging on all high-cardinality paths. |
| Warranty | `/warranty`; `GET /api/v1/warranty`, admin upload `POST /api/v1/warranty/upload`. | Coverage summary, upload feedback, invalid-row and match counts, search/paging. | Verify pagination/summary semantics and auditable upload outcome visibility. |
| Audit / ISE actions | `/audit`, `/ise-actions`; `GET /api/v1/audit/ise-actions`, `/api/v1/ise/actions/state`, `/api/v1/ise/status`. | Filterable append-only audit table with actor, target, action, time, outcome; current action state separate from history. | Confirm server-side paging and filters for long audit retention. |
| ISE sharing feed | `/ise-feed`; `/api/v1/integrations/ise/endpoints`, sharing-rule GET/history/PUT. | Read-only feed preview, explicit sharing scope, rule history, admin edit form. | Ensure secret data is absent; define pagination and freshness contract. |
| Policies | `/policies`; `/api/v1/policy/apps`, `/history`, PUT. | Active policy, diff/history, validation, confirmation and save outcome. | Audit trail linkage and large policy handling are not established from UI contract. |
| Users | `/users`; `/api/v1/users`, user mutation routes. | Admin-only list, role, enabled, create/edit, safe password reset and confirm delete. | Confirm API audit events and pagination if user count grows. |

## Proposed additions (contract first)

| Proposed page | Depends on existing API? | Missing contract / boundary |
|---|---|---|
| Worker fleet operations | Existing `/api/v1/admin/workers` GET returns worker name, shard, enabled, `lastSeenAt`, and derived ACTIVE/STALE/REVOKED state; POST enrollment-token and `/{id}/revoke` are also present. `/api/v1/system/health` is not worker fleet health. | Build the initial page from existing list contract and existing role boundary (ADMIN only). **Missing:** last claim/result, worker version, pending outbox count/oldest age, delivery error. Approval required before adding those contract fields or persistence. |
| Result delivery / failures | Existing worker result endpoint accepts result and returns durable receipt; backend has receipt schema and admin-visible worker routes are not a delivery ledger. | **Missing:** operator query of accepted/rejected/replayed results, reconciliation and receipt lifecycle visibility. Decide whether this is sourced from receipts, metrics, or a dedicated operational record. Do not expose raw sensitive payload by default. |
| Evidence/audit explorer | Endpoint detail and ISE action audit are available; evidence records are available by domain APIs. | **Partial:** there is no single cross-domain evidence search contract. Prefer links to existing domain APIs before proposing a unified search endpoint. |
| Platform metrics | `/api/v1/system/health` exposes a snapshot; Prometheus Actuator endpoint is configured. | No authenticated browser metrics contract is verified. Use the approved observability stack rather than scraping internal metrics into a new UI endpoint without a decision. |

## Reusable component work sequence

1. Standardize `PageHeader`, `FilterBar`, `FreshnessLabel`, and explicit `LoadingState` / `EmptyState` / `ErrorState` / `StaleState` behavior.
2. Clarify `DataTable` mode: client dataset versus server pagination; include accessible sorting, row count, filter count, and export scope.
3. Define typed domain components for `EndpointStatus`, `JobState`, `WorkerHealth`, and evidence timelines only after contracts are approved.
4. Add a responsive navigation drawer and mobile record-summary treatment while preserving accessible table alternatives.
5. Add chart text/table alternatives and semantic descriptions; validate keyboard and screen-reader behavior.

## Acceptance criteria for later implementation

- Each page names its API contract and distinguishes no data, no matches, partial data, stale data, permission denial, and request failure.
- 50k endpoint browsing is server-paged and does not issue per-endpoint calls for the full fleet.
- Worker fleet status uses a documented freshness threshold and does not infer worker connectivity from backend health.
- Job lists label their completeness and support approved server-side filters/paging.
- Every security state is communicated with text and not color alone.
- Responsive, keyboard, contrast, zoom and reduced-motion checks are recorded before release.
