# Proposed Endpoint Posture design system

This is a design-review version of the product baseline in [`../DESIGN.md`](../DESIGN.md). It retains the graphite/neutral surfaces, teal action accent, dual themes, 4px spacing base, low elevation, text-first status, and WCAG 2.2 AA target. It sharpens usage rules and introduces the approval question of whether to make light theme the default. Do not silently change the existing product baseline.

## Visual direction

Operational, evidence-led, calm. Put severity, freshness and endpoint identity before decoration. The platform should feel like security operations software through dense evidence hierarchy, precise states and clear provenance—not through threat imagery, neon accents, gradients, or oversized metric tiles.

## Color tokens

The values below retain the current proposed baseline. Text pairings and non-text control contrast must be verified before implementation.

| Role | Light | Dark |
|---|---|---|
| Canvas | `#F6F7F5` | `#0F1110` |
| Surface | `#FFFFFF` | `#161918` |
| Surface muted | `#EEF1EF` | `#121514` |
| Border | `#D8DEDA` | `#303734` |
| Text primary | `#18201C` | `#F0F3F1` |
| Text secondary | `#52615A` | `#AAB5AF` |
| Action teal | `#087E75` | `#35C6B5` |
| Action hover | `#06665F` | `#62D9CB` |
| Success | `#167044` | `#55D68B` |
| Warning | `#8A5700` | `#F2BE55` |
| Critical | `#B4232D` | `#FF737D` |
| Informational | `#245EA8` | `#77AEFF` |
| Neutral/unknown | `#52615A` | `#AAB5AF` |

Apply semantic colors consistently: compliant/healthy/complete/delivered = success; stale/partial/attention = warning; noncompliant/failed/disconnected = critical only when that is the contract meaning; unknown/no assessment/not reported = neutral. Never convert missing evidence into success. A status always has a visible text label; icons supplement text. Do not use action teal as generic decoration.

## Typography

Retain system sans: `Segoe UI`, Inter, system UI. Retain system monospace for MAC/IP/IDs/technical values. Use tabular numerals for counts and time. Suggested scale:

| Style | Size / line height | Weight | Use |
|---|---|---|---|
| Page title | 24–28 / 1.2 | 650–700 | One per page |
| Section heading | 16–18 / 1.35 | 600 | Card/section title |
| Body | 14 / 1.45 | 400 | Labels and explanatory copy |
| Dense table | 13 / 1.4 | 400–500 | Operational rows |
| Helper/meta | 12 / 1.4 | 400 | Freshness, source, secondary metadata |
| Numeric KPI | 24–30 / 1.1 | 600–700 | Compact count only |

Avoid uppercase microtext for important labels and do not reduce essential text to current 10px captions.

## Spacing, borders and elevation

- Base 4px scale: 4, 8, 12, 16, 24, 32, 40.
- App shell: desktop sidebar 232–248px; main inset 28–32px; tablet inset 20px; mobile inset 16px.
- Content gaps: 16px within a control group, 20–24px between sections.
- Radius: 6px controls, 8px cards/tables, 10px dialogs. Avoid nested rounded-card-on-rounded-card patterns.
- Use 1px borders for grouping. Shadows only for menus/dialogs; no permanent card shadow. No glassmorphism or decorative gradients.

## Navigation

Persistent desktop navigation groups Overview, Endpoints, Compliance, Jobs, Workers (ADMIN-only), Inventory, and Administration. Keep current domain-specific routes for hardware, software, ports, warranty, audit and ISE. Use one stable Lucide icon per destination; pair icons with text. Active route uses a left rule and label weight plus teal text. Tablet may collapse to a labeled compact rail; mobile uses a drawer opened by a 44px button and closes after selection. Provide breadcrumb/back link on endpoint and job details. Search must expose keyboard focus, loading/results/no-results and accessible combobox semantics.

## Cards and tables

Metric cards are compact and comparable, with label, value, period/source and freshness. Avoid more than four primary KPIs in a row. Cards should not replace records. Tables use explicit headers, aligned numbers, row focus, stable sort indicators and named row actions. On fleet-sized data, use backend pagination/search/filter; client pagination must be labeled as the loaded dataset. Export states its scope. Mobile uses cards or prioritized columns and must not hide identity/status/time.

## Forms, buttons and dialogs

- Primary button: solid teal, one per action group; secondary is outlined; destructive uses critical color and clear verb.
- Minimum 40px desktop and 44px touch target mobile; visible keyboard focus.
- Persistent field labels, explicit units, inline validation and summary for multi-field errors.
- Confirmation names target and consequence; no destructive action on hover or ambiguous icon-only control.
- Dialog uses semantic `dialog`/`aria-modal`, focus trap, Escape behavior, initial focus and focus return. Current `ConfirmDialog` needs this accessibility review before it is reused.

## Badges, notifications and states

Badge includes concise state text and optional icon. Live status also includes “last checked”. Stale data retains the last value and clearly labels age. Error notification states what failed and offers a safe retry where available. Loading uses stable skeletons or inline progress without shifting page layout. Empty explains why and the supported next action. Permission denied is distinct from empty. Use `aria-live` for async result/error summaries, not for every polling tick.

## Charts

Use chart only for time trend/distribution supported by a real data contract. Include range, units, source/freshness and accessible textual/table equivalent. Use consistent semantic colors and direct labels. No OS distribution visualization until supported. Avoid a chart of fabricated sample values in a live application; visual preview labels remain illustrative.

## Reusable components

`AppShell`, `PrimaryNavigation`, `GlobalSearch`, `PageHeader`, `FilterBar`, `MetricCard`, `FreshnessLabel`, `StatusBadge`, `ServerDataTable`, `MobileRecordList`, `Pagination`, `EvidenceTimeline`, `LoadingState`, `EmptyState`, `ErrorState`, `StaleState`, `ConfirmDialog`, and `ChartWithSummary`. Components receive typed state; transport stays in domain data hooks. Use existing components where their behavior fits, and remove redundant abstractions only after call-site review.

## Accessibility and motion

Target WCAG 2.2 AA. Verify text/control contrast in both themes, 200% zoom, keyboard-only, screen-reader names/state, focus order, dialog behavior, reduced-motion, table semantics, chart alternatives and error announcements. No status conveyed only by color, animation, icon, or position. Respect `prefers-reduced-motion`; polling never needs a pulsing dot to imply freshness.

## Theme decision

The current implementation defaults to dark when no theme cookie is set. This proposal's mockups use a light operational surface to make dense tables and evidence easy to scan, while retaining dark mode. **Approval required** before changing default; until then, the mockup is a visual direction, not a committed theme decision.
