# Frontend implementation roadmap (proposed)

Priorities: P0 correctness/accessibility risks; P1 operational navigation and current-contract usability; P2 workflow refinements; P3 future API-backed capabilities. This sequence requires design/contract approvals in [`APPROVAL-CHECKLIST.md`](APPROVAL-CHECKLIST.md). It is not authorization to begin implementation.

## Phase 0 — resolve product and contract decisions

1. Approve theme default or explicitly retain dark-first. Keep both themes regardless.
2. Confirm roles allowed to access worker registry (existing backend currently ADMIN-only).
3. Confirm the `/jobs` screen is a bounded recent view until server-side pagination/detail contracts exist.
4. Confirm current CSV export scope for each page.
5. Decide whether OS distribution, result delivery status, attempt history, global indicator inventory, and report history merit backend contracts.

**Exit:** approved design tokens, API gaps accepted, no visual concept depicts unsupported data as available.

## Phase 1 — accessible shell and state semantics (P0/P1)

- Make login password visibility keyboard accessible; audit hit targets.
- Separate auth 401 from optional ISE availability in shell behavior, following explicit backend/session approval.
- Implement mobile navigation, skip link, page landmarks, accessible global search states and dialog focus behavior.
- Standardize loading/empty/error/permission-denied/stale states and freshness labels.

**Acceptance:** keyboard and screen-reader path works; ISE degradation does not masquerade as an empty page; status and freshness remain textual.

## Phase 2 — core endpoint operations (P1)

- Refine endpoint inventory to use current server page contract and accurate loaded-scope labels.
- Refine endpoint detail with per-section freshness and partial-failure states, preserving current evidence APIs.
- Refine compliance/hardware lists without introducing unverified risk fields.

**Acceptance:** no full-fleet browser loading; mobile record summaries preserve identity/status/time; evidence absence and collection failure are distinct.

## Phase 3 — job monitoring and worker registry (P1/P2)

- Refine current bounded jobs list and queue counts; label list completeness; remove false progress affordances.
- Add a worker registry UI using existing ADMIN list/enrollment/revoke APIs and their actual DTO fields.
- Add active-job, last execution, attempt history, diagnostics or outbox delivery UI only after API support is approved and available.

**Acceptance:** current job contract is represented accurately; worker state comes from heartbeat state; unsupported delivery fields are not shown as metrics.

## Phase 4 — security indicators, reports and settings (P2/P3)

- Improve endpoint-level security finding presentation, evidence disclosure and history.
- Keep current domain CSV exports with explicit source/scope and filter behavior.
- Refine current user, application policy, and ISE sharing-rule settings.
- Add global indicator/report-center/authentication configuration only after corresponding API/security decisions.

**Acceptance:** possible indicators are not presented as confirmed incidents; no unsupported scheduled/report/config action appears.

## Phase 5 — design quality gate

- Validate light and dark contrast; screen-reader labels; keyboard; reduced motion; 1440/768/390/320 widths; 200% zoom.
- Test empty, slow, failed, stale, partial, unauthorized and success data states with representative fixtures.
- Review request counts for overview/detail and server paging for 50k-endpoint fleet.

**Acceptance:** review evidence recorded by page and state. No application tests are run as part of this planning task.
