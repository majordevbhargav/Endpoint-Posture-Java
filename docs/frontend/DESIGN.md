# Endpoint Posture design system proposal

Status: proposed; design planning only. This is a product-specific direction informed by the references in [`design-references/README.md`](design-references/README.md). It does not authorize UI implementation.

## Visual principles

- Make fleet risk and operational exceptions scannable before decorative content.
- Favor a calm, high-contrast neutral canvas with teal as the product action accent. Reserve semantic colors for actual status.
- Keep density adjustable through information hierarchy: concise summary, filterable table, then detail and evidence.
- Use consistent meaning and placement for a status across dashboard, list, detail, and audit surfaces.
- Prefer borders and spacing to shadows; reserve elevation for overlays and menus.

## Color tokens

Proposed starting tokens. Validate contrast in both themes before implementation.

| Token | Light | Dark | Use |
|---|---|---|---|
| canvas | `#F6F7F5` | `#0F1110` | App background |
| surface | `#FFFFFF` | `#161918` | Cards, tables, dialogs |
| surface-muted | `#EEF1EF` | `#121514` | Table headers, grouped controls |
| border | `#D8DEDA` | `#303734` | Dividers and outlines |
| text | `#18201C` | `#F0F3F1` | Primary content |
| text-muted | `#52615A` | `#AAB5AF` | Secondary labels |
| action | `#087E75` | `#35C6B5` | Links, selected navigation, primary actions |
| action-hover | `#06665F` | `#62D9CB` | Hover and active action |
| status-success | `#167044` | `#55D68B` | Compliant, connected, delivered |
| status-warning | `#8A5700` | `#F2BE55` | Stale, partial, attention needed |
| status-critical | `#B4232D` | `#FF737D` | Noncompliant, failed, disconnected |
| status-info | `#245EA8` | `#77AEFF` | Informational state |
| status-neutral | `#52615A` | `#AAB5AF` | Unknown, not assessed |

Never encode status by color alone: include text and, where helpful, a stable icon. Error, warning, success, and neutral states must remain distinguishable in grayscale and common color-vision deficiencies.

## Typography

Use the existing system sans stack (`Segoe UI`, Inter, system UI) unless product font licensing and loading are decided later. Use a system monospace stack for IDs, hashes, timestamps when alignment helps, and machine values. Suggested UI scale: page title 24–30px/1.2 semibold; section title 16–18px/1.35 semibold; body/table 13–15px/1.45; helper/caption 12px/1.4. Avoid tiny uppercase labels for essential information. Tabular numerals should be used for counts, rates, and timestamps.

## Spacing and layout

Use a 4px base spacing scale (4, 8, 12, 16, 24, 32, 40). Keep the global content area fluid with a readable maximum width for narrative/detail pages and full width for inventory tables. Use 1px borders and 6–10px corner radii. Avoid fixed-height content panels when they clip zoomed text. Align title, filters, summary, and table edges consistently.

## Navigation

Keep persistent primary navigation for Overview, Endpoints, Compliance, Jobs, and System. Group inventory and administration under clearly named secondary sections. Show active route with text and a non-color indicator. On narrow screens, collapse to an accessible drawer with a visible menu button; preserve route names and keyboard focus. Global search should state its scope and provide a keyboard-operable result list. Do not use color-only connection dots.

## Tables and data-heavy layouts

- Provide server-side paging, sorting, and filtering for fleet-scale collections; do not load tens of thousands of rows into the browser.
- Keep column labels explicit, align numeric values, and truncate only with an accessible way to inspect the full value.
- Keep endpoint identity and status visible while horizontal scrolling where feasible; allow mobile cards as an alternative to an unusable wide table.
- Persist filter state in URL query parameters when safe and useful; provide a clear reset action and visible active-filter count.
- Distinguish no results from no data, loading, and failed request. Announce table result counts and sort changes to assistive technology.
- CSV export must communicate whether it exports the current page or all filtered results. Avoid exporting beyond authorized scope.

## Cards and status indicators

Use cards for a small set of summary metrics, service health, and related actions, not as a replacement for sortable records. Each status display includes label, text state, and last-checked/updated time where relevant. Distinguish unknown from healthy. Show stale data age and preserve the last known value when refresh fails, with an explicit stale/error marker.

## Charts

Use charts only when trend or distribution answers a real operational question. Include a title, period, units, accessible text/table equivalent, and precise hover/focus details. Use direct labels where possible and the same semantic palette as badges. Avoid 3D, gradients that imply unsupported severity, and overloaded dual axes. Charts must resize without losing readable labels.

## Forms and filters

Label every field persistently. Place validation beside the field and summarize submission errors. Use explicit units and defaults; preserve values on recoverable errors. Destructive or enforcement actions require a clear target, consequence, permission-aware availability, and confirmation. Filters should be keyboard accessible and provide an explicit applied state.

## Responsive behavior

At desktop widths, use the persistent sidebar and dense table. At tablet widths, reduce secondary columns and allow sidebar collapse. At phone widths, convert tables to prioritized record summaries or allow deliberate horizontal scrolling; retain identity, state, and key timestamp. Never hide critical security state or actions solely to fit. Test at 320px, 768px, and 1280px widths plus 200% zoom.

## Accessibility

Target WCAG 2.2 AA. Ensure text and UI contrast, visible focus, semantic landmarks/headings, keyboard operation, accessible names, error announcements, reduced-motion support, and non-color status encoding. Use at least 44px touch targets where practical. Keep focus inside modal dialogs and return focus to the invoking control. Do not communicate connectivity using animation alone.

## Reusable component conventions

Build shared primitives for AppShell/Navigation, PageHeader, FilterBar, DataTable, Pagination, StatusBadge, FreshnessLabel, MetricCard, EmptyState, ErrorState, LoadingState, ConfirmDialog, and accessible chart wrapper. Components receive typed data and explicit state; domain pages own data orchestration. Keep API transport out of presentational components. Standardize loading/empty/error/success behavior and document whether pagination and export are client-side or server-side. Do not add an abstraction until multiple pages share the same behavior.
