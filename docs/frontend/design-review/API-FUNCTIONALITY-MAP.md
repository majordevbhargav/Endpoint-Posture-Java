# Frontend capability and API map

This map separates UI behavior present in source from backend route support and proposed capability. Paths are existing routes verified in frontend clients/backend mappings; no future route names are introduced. Worker API details were inspected in `WorkerAdminController`, `WorkerRegistryService`, and worker DTOs. No live request was made.

| Area | Existing frontend | Verified backend support | Design proposal / gap |
|---|---|---|---|
| Authentication | `/login`, client token/session storage, login error and lockout message. | `POST /api/v1/auth/login`; auth/security filters and role guards. | Improve keyboard reachability and error states. No verified UI API for MFA/IdP/auth configuration. Keep current auth behavior pending separate session/ISE decision. |
| Dashboard | Summary, 7-day trend, categories, connected/at-risk endpoint slices, ISE audit preview; bulk posture enqueue. | `GET /api/v1/dashboard/summary`, `/trend`, `/categories`; `/api/v1/endpoints/page`; `/api/v1/audit/ise-actions`; `POST /api/v1/jobs/bulk`. | OS distribution not in verified dashboard DTO. Worker fleet widget not currently provided by summary. Future changes require API/role review. |
| Endpoint inventory | Search, page, connected/status filters, posture/hardware expansion, single row check actions, CSV. | `GET /api/v1/endpoints/page` with page/size/q/connected/status; `GET /api/v1/posture/latest/batch?ids=`; endpoint names/briefs; job create/bulk. | Existing pagination is real; add sorts/filter values only when supported. Confirm export scope and whether current export contains full matching set. Risk score is not present in `EndpointListItem`. |
| Endpoint detail | Endpoint header; posture/hardware, diagnostic/security history, sessions, endpoint jobs, ISE action audit; run checks and supported ISE actions. | `GET /api/v1/endpoints/{id}`; `/posture[/latest]`; `/hardware-health[/latest]`; `/diagnostics[/latest]`; `/security-indicators[/latest]`; `/sessions`; `/jobs/endpoint/{id}`; `/audit/ise-actions?endpointId=`; job and ISE action POSTs. | No single aggregate detail endpoint. Software/port endpoint-specific details are not verified. Preserve per-section partial failures. |
| Compliance | Fleet matrix, filters, latest posture batch, category data, bulk posture check. | Endpoint page, `/api/v1/posture/latest/batch`, dashboard summary/categories, jobs. | Add category-specific server paging/sort only after contract verification; do not infer unsupported risk fields. |
| Hardware | Fleet page, score bands/summary, endpoint inspect, individual/bulk hardware check. | `/api/v1/hardware-health/page`, `/summary`, `/latest`; endpoint hardware history; jobs. | Current contract covers hardware telemetry, not general fleet worker health. |
| Jobs | Latest bounded jobs (`listJobs(500)`), queue counts, refresh pause, enqueue by endpoint/type. | `GET /api/v1/jobs?limit=...`, `/endpoint/{id}?limit=...`, `POST /api/v1/jobs`, `/bulk`; queue in `/api/v1/system/health`. Job DTO includes status/type/attemptCount/maxAttempts/error and lifecycle timestamps. | No job detail route, per-attempt history, percent progress, worker attribution, server-side search/page, or outbox delivery status verified. Keep them absent or mark future. |
| Worker registry | No current route/page. | ADMIN-only `/api/v1/admin/workers` GET list; POST `/enrollment-tokens`; POST `/{id}/revoke`. `WorkerView`: id/name/shard/enabled/createdAt/lastSeenAt/revokedAt/createdBy/status. State is ACTIVE/STALE/REVOKED by heartbeat threshold. | Registry, enrollment and revoke screen can use existing contract. No active jobs, worker version, last successful execution, diagnostics, pending outbox count/age, or result-delivery details verified. |
| Security indicators | Endpoint detail tab shows latest, findings, severity/risk level, sample summary, evidence and history. | `GET /api/v1/endpoints/{id}/security-indicators[/latest]`; ingest endpoint is worker/agent-facing. | No fleet-wide findings inventory, global severity filter, aggregate event history or endpoint association search contract. Do not create a global page as if supported. |
| Reports/export | CSV utilities and some table exports; dashboard trend; audit and domain history. | Domain list/history/summary APIs listed above; warranty list/upload. | No generic report generation, scheduled delivery, saved report, historical report, or PDF API verified. Label current CSV source/scope only. |
| Applications/ports | Search/filter/page and summaries, CSV. | `/api/v1/applications/page`, `/summary`; `/api/v1/ports/page`, `/summary`; full-list routes also exist. | Use page APIs for high-cardinality data; explicitly scope export. |
| Warranty | Records and CSV upload. | `GET /api/v1/warranty`; ADMIN `POST /api/v1/warranty/upload` returns saved count and row errors. | No report history or upload audit page contract verified beyond response. |
| Audit / ISE actions | Audit and current action-state pages, polling, status. | `/api/v1/audit/ise-actions`, `/api/v1/ise/actions/state`, `/api/v1/ise/status`; ISE action POSTs with role guards. | Confirm query completeness/retention before presenting as full audit ledger; separate event history from current state. |
| Policy/ISE sharing | App policy editor/history and ISE sharing-rule editor/feed. | `/api/v1/policy/apps` GET/PUT/history; `/api/v1/integrations/ise/sharing-rule` GET/PUT/history and paged `/endpoints`. | Supported config is limited to app policy and ISE sharing scope; not authentication settings or generic system config. |
| System health | API/database/ISE/queue/backend local worker mode. | `GET /api/v1/system/health`; `/api/v1/ise/status`. | Do not interpret local backend pool (`LOCAL` / `REMOTE_PENDING`) as remote worker-fleet health. Use admin registry for heartbeat only; delivery metrics are missing. |
| Users/roles | ADMIN user list/create/update role/enable/reset/delete. | `/api/v1/users` GET/POST, `PATCH /{id}`, `POST /{id}/password`, `DELETE /{id}`; admin guard. | Existing API supports user administration. No UI contract for permission authoring or authentication provider configuration. |

## Verified UI type constraints

- `JobResponse` has aggregate attempt count and max attempts, not individual attempt records or progress.
- `SystemHealth.Workers` has enabled, thread count, and `LOCAL`/`REMOTE_PENDING` mode; it is a backend pool indicator.
- `DashboardSummary` has total/connected/notConnected/compliant/nonCompliant/error/unassessed/stale; no OS distribution or worker metrics.
- `SecurityIndicatorResponse` is endpoint-scoped and contains finding evidence and collected time; it is not a normalized global event contract.
- `WorkerRegistryService.WorkerView` carries heartbeat freshness state but no active job or delivery data.

## Data integrity and security guardrails for UI

- Keep ADMIN-only worker/user/settings controls hidden in the UI but rely on backend authorization as final authority.
- Do not show raw worker result payloads as general audit data.
- Do not interpret a missing scan as compliant; preserve `WINRM_UNAVAILABLE`/`FAILED` as separate states.
- State sample metrics in mockups are illustrative, not product/production data.
