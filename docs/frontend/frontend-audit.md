# Frontend audit

Scope: source inspection of the existing Next.js App Router frontend. No frontend files or tests were changed or run.

## Current structure and composition

- `frontend/app/page.tsx` is the root entry; `/login` is a separate route. `app/(dashboard)/layout.tsx` wraps the dashboard route group with auth gating, `Sidebar`, `Topbar`, ISE banner, tab status, and scrollable main content.
- Existing dashboard routes: `/overview`, `/endpoints`, `/endpoints/[id]`, `/compliance`, `/hardware`, `/jobs`, `/applications`, `/ports`, `/warranty`, `/policies`, `/audit`, `/ise-actions`, `/ise-feed`, `/system`, and `/users`.
- Shared layout components: `components/layout/{Sidebar,Topbar,ThemeToggle,TabStatus}.tsx`; shared UI: `DataTable`, `PaginationBar`, `StatusBadge`, `ConnectionDot`, `IseBanner`, `ConfirmDialog`; dashboard visualizations: `StatCard`, `TrendChart`, `StatusDonut`, `RiskList`, `ActivityFeed`, `RingGauge`.
- Styling uses Tailwind plus CSS custom properties in `app/globals.css`; current palette is dark graphite/teal with a light theme. `ThemeToggle` provides theme selection. The app uses Lucide icons and system font stacks.
- Data access is client-side through `lib/api.ts` and `lib/apiExtra.ts`; supporting code includes `session.ts`, `auth.ts`, `permissions.ts`, `usePolling.ts`, `useCachedFetch.ts`, `usePagination.ts`, and inventory helpers. `next.config.mjs` rewrites `/api/*` to `BACKEND_URL`; the frontend Dockerfile bakes this at build time.

## Existing strengths

- Real operational pages already exist; there is no empty-shell assumption.
- Typed API response models cover endpoints, assessments, hardware, jobs, system health, ISE status/actions and users.
- `DataTable` provides loading and empty rows, page controls and CSV export; several pages provide explicit polling intervals and bulk enqueue actions.
- UI permission helpers hide the user-management link for users without that action, and backend controllers enforce role checks for sensitive actions.
- `useCachedFetch` retains prior overview data while refreshing and supports errors; system/sidebar surfaces preserve last health snapshots when the request fails.
- Login/session, CSV and pagination helpers have existing unit tests (inspected, not executed).

## Findings and design implications

1. **Authentication is coupled to ISE availability.** Dashboard layout waits on `api.iseStatus()` before showing routes and clears the token on any error. A temporary ISE/backend network error can redirect a still-authenticated user to login. Separate 401/session failure from integration degradation.
2. **Worker fleet operations are missing from navigation and the data contract.** `/system` displays backend health and queue counts. `/api/v1/admin/workers` lists workers but the UI has no worker registry/health page. The `SystemHealth.Workers` contract is backend worker pool enabled/threads/mode, not enrolled worker freshness or result delivery.
3. **Job monitoring is bounded and client-polled.** `/jobs` polls jobs every 4 seconds and asks `listJobs(500)`; there is no source-visible server-side page/filter contract for job history. The page should not imply it is a complete job ledger.
4. **Table API semantics vary.** The shared `DataTable` paginates/export rows already loaded in memory, while endpoints and hardware use server pages. Product should label export scope and migrate large datasets to server-side sort/filter/export.
5. **Endpoint detail aggregates many contracts.** The details route loads endpoint, posture latest/history, hardware latest/history, jobs, ISE audits, sessions, diagnostics, and security indicators; failures are caught independently for some optional requests. Document data freshness and partial failure clearly; consider request fan-out and page load impact.
6. **Responsive behavior is not proven by source inspection.** Dashboard shell uses fixed `w-64` sidebar and `h-screen`; content generally uses responsive Tailwind classes but wide tables set minimum widths. No browser or viewport validation was run. Add responsive navigation and table/card behavior to a later design acceptance pass.
7. **Accessibility evidence is incomplete.** Components use semantic HTML in places and shared buttons/dialogs, but no audit was run. Verify focus, keyboard operation, contrast in both themes, chart alternatives, field labels, table headers, modal focus, status text and reduced motion.
8. **Error and empty states are unevenly orchestrated.** DataTable has loading and empty states. Several pages catch errors locally, sometimes set empty arrays or ignore optional request errors; that can make a service failure appear as no records. Standardize stale-data, recoverable error, empty, permission-denied and success states.
9. **Theme and token conventions exist but need design governance.** CSS defines semantic variables, yet Tailwind class usage directly encodes arbitrary sizes and colors on pages. Adopt the proposed product token and reusable component conventions before adding new visual patterns.
10. **Build-time backend routing is a deployment constraint.** Docker `ARG BACKEND_URL` and Next rewrites mean changing backend origin requires rebuilding the frontend image. Confirm this is intentional for each environment or propose runtime proxy configuration as a separate architecture decision.

## Existing backend contract map

All paths below are verified in backend controller mappings and frontend client usage. They are not proposed new endpoints.

| Domain | Existing route(s) |
|---|---|
| Auth/users | `POST /api/v1/auth/login`; `/api/v1/users` GET/POST; `PATCH /{id}`, `POST /{id}/password`, `DELETE /{id}` |
| Endpoint fleet | `/api/v1/endpoints`, `/page`, `/names`, `/briefs`, `/{id}` |
| Posture | `/api/v1/endpoints/{id}/posture`, `/latest`; `/api/v1/posture/latest`, `/latest/batch`; `POST /api/v1/posture` is ingest |
| Hardware | `/api/v1/hardware-health/latest`, `/page`, `/summary`; endpoint `/{id}/hardware-health`, `/latest`; ingest `POST /api/v1/hardware-health` |
| Jobs/workers | `/api/v1/jobs`, `/endpoint/{endpointId}`, `POST /api/v1/jobs`, `/bulk`; worker protocol `/api/v1/workers/*`; admin `/api/v1/admin/workers` |
| Detail evidence | `/api/v1/endpoints/{id}/diagnostics[/latest]`, `/security-indicators[/latest]`, `/sessions`; `/api/v1/audit/ise-actions` |
| Dashboard/health | `/api/v1/dashboard/summary`, `/trend`, `/categories`; `/api/v1/system/health`; `/api/v1/ise/status` |
| Inventory/admin | `/api/v1/applications[/page|/summary]`, `/api/v1/ports[/page|/summary]`, `/api/v1/warranty`, `/api/v1/policy/apps`, `/api/v1/integrations/ise/*` |

## Verification limits

No browser rendering, frontend build, tests, accessibility scanner, API live calls, or load checks were run. Conclusions about responsive rendering and runtime error behavior are based on inspected source, not execution. No frontend/backend contract was changed.
