# Approval checklist

Resolve these items before production frontend implementation. The proposal preserves current behavior unless otherwise approved.

## Product design

- [ ] **Theme default:** approve light-first operational UI (dark remains available), or retain current dark default. Existing app defaults to dark; review mockups are light.
- [ ] **Navigation labels and grouping:** approve Overview / Endpoints / Compliance / Jobs / Workers / Inventory / Administration grouping while retaining all current routes.
- [ ] **Worker page access:** use current ADMIN-only authorization, or request an explicit backend authorization change for another role.
- [ ] **Security indicator language:** retain “possible indicators” / sampled evidence rather than alert/incident language.
- [ ] **Dashboard OS distribution:** defer until backend supplies a trustworthy distribution field or approve a backend contract proposal.

## API/product scope

- [ ] **Worker health:** approve a frontend registry using existing heartbeat state; decide separately whether to add active jobs, last success, version, diagnostics, pending outbox age/count, and delivery failures.
- [ ] **Jobs:** approve a bounded “latest jobs” view until page/search/detail/attempt APIs exist; do not imply percent progress.
- [ ] **Result delivery:** decide whether operator UI should show outbox/delivery status and what data can safely be exposed.
- [ ] **Security indicators:** approve endpoint-only view for now or commission a fleet-level API contract.
- [ ] **Reports:** approve clear domain CSV exports only for now or commission saved/scheduled/historical report contracts.
- [ ] **Settings:** confirm that authentication configuration remains operations-managed; only current user, app policy and ISE sharing controls appear in UI.
- [ ] **Export semantics:** establish whether each CSV is current page, currently loaded filtered rows, or all matching server rows; preserve authorization limits.

## Accessibility and rollout

- [ ] Approve WCAG 2.2 AA target and mobile touch-target requirement.
- [ ] Approve browser review at 1440px, 768px, 390px, 320px, and 200% zoom.
- [ ] Approve keyboard and screen-reader verification for search, filters, tables, alerts, theme switch and dialogs.
- [ ] Confirm implementation should wait until the current branch/worktree user changes are reviewed independently.

**No source implementation should begin until the required product/API decisions above are approved.**
