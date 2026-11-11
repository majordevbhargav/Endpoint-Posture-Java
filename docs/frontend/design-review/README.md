# Endpoint Posture — frontend design review

**Status:** design proposal for review only. No production frontend/backend source, API, dependency, or configuration was changed.

## Executive proposal

Shape the existing application into an operations console for endpoint security teams: a compact persistent navigation frame; a clear fleet and exception overview; server-backed endpoint and job lists; a focused endpoint evidence record; and a worker registry view using the existing admin API. Use a restrained graphite, warm-neutral, and teal system with semantic status colors. Keep the existing product name and icon language as brand assets; do not imitate the selected design references.

The current frontend already has broad working functionality. This proposal redesigns and clarifies it rather than replacing it with a generic admin shell. The most valuable changes are to put evidence freshness beside status, separate backend health from endpoint-worker health, make table/export scope explicit, and present failed, stale, partial, and empty data distinctly.

## Design references and skill availability

The prior design reference document inspected the actual IBM Carbon, Sentry, HashiCorp, and Linear `DESIGN.md` files in Awesome Design MD and recorded MIT attribution in [`design-references/README.md`](../design-references/README.md). This review uses that research and the existing product baseline in [`../DESIGN.md`](../DESIGN.md). In the current skill catalog, Untangle Codebase is installed, but there is no separate Awesome Design MD skill or Frontend Design skill. I did not claim to invoke either missing skill; this review is grounded in the available design files, inspected frontend, and design principles already documented for this product.

## High-level recommendations

1. Refine the current `/overview`, `/endpoints`, endpoint detail, and `/jobs` pages first; these are the operational core and are rendered as responsive previews.
2. Add a Worker Fleet view using existing `GET /api/v1/admin/workers` data (ADMIN-only). Show its actual `lastSeenAt` and ACTIVE/STALE/REVOKED state. Keep active-job and outbox-delivery fields absent until a reviewed backend contract supplies them.
3. Do not present OS distribution, cross-fleet security findings, attempt history, generic reports, authentication configuration, or global result delivery as available today. The verified UI/API contract does not provide these views.
4. Use 1440px as full desktop, 768px as compact tablet, and 390px as mobile. At narrow sizes, switch to a drawer and record summaries instead of forcing wide tables.
5. Preserve the existing semantic theme tokens and teal accent. The proposed light-first presentation conflicts with the current dark default; approval is needed before changing the default theme. Keep both themes during implementation.

## Visual concepts

Open the standalone responsive previews in [`mockups/index.html`](mockups/index.html). They contain dashboard, endpoint inventory, job monitoring, and worker registry concepts. The data is marked illustrative. Resize the browser to 1440px, 768px, and 390px to review desktop, tablet, and mobile compositions. These are mockups, not live pages or screenshots of production data.

## Package contents

- [Page-by-page review](PAGE-BY-PAGE-REVIEW.md): existing pages, proposed hierarchy, usability review, and responsive concepts.
- [Design system](DESIGN-SYSTEM.md): tokens and reusable component behavior, reconciled with the existing product `DESIGN.md`.
- [Responsive specification](RESPONSIVE-SPECIFICATION.md): breakpoint behavior and page-specific adaptations.
- [API/functionality map](API-FUNCTIONALITY-MAP.md): verified support, UI coverage, and explicit gaps.
- [Implementation roadmap](IMPLEMENTATION-ROADMAP.md): phased frontend sequence and acceptance checks.
- [Approval checklist](APPROVAL-CHECKLIST.md): choices that should be resolved before implementation.

## Baseline conflicts to resolve

- **Theme default:** current CSS renders dark by default; the existing product proposal defines both themes without selecting a default. This review recommends light-first for dense daily operations while retaining dark mode. Approval required.
- **Worker operations:** prior audit wording says worker-health data is missing. Current backend admin registry does provide `lastSeenAt` and derived ACTIVE/STALE/REVOKED status; the frontend simply does not expose it. Delivery backlog, active jobs, and last successful execution remain missing.
- **Dashboard scope:** OS distribution was requested, but no verified OS distribution metric appears in `DashboardSummary`/dashboard API. Show it only as an explicitly unavailable/future module until a contract exists.
- **Security indicators:** endpoint-level histories exist, but no global inventory/search endpoint is present. Do not imply a global security-event feed.
- **Reports:** CSV export exists for selected tables, but no general report generation/history API is present. Keep “Reports” as a future capability or describe current exports accurately.
- **Admin settings:** user/role administration, application policy, and ISE sharing-rule configuration exist. Authentication/system configuration do not have verified UI contracts and should not be presented as editable controls.

## Review boundary

This package stops at design and review. It does not authorize source edits, API changes, package installation, test execution, or deployment. Wait for explicit approval before implementation.
