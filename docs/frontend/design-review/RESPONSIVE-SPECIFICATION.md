# Responsive design specification

The responsive concepts are drawn at 1440px desktop, 768px tablet and 390px mobile. These are design targets; the source implementation has not been browser-tested at these sizes. Use fluid containers, avoid viewport-wide overflow, and test at 320px and 200% zoom in implementation review.

## Global breakpoints and frame

| Viewport | Frame | Navigation | Content |
|---|---|---|---|
| 1440px desktop | 236px sidebar, 56px topbar, 28–32px content inset | Full grouped labels and status text | Four-column KPI max; two-column work area; server tables |
| 768px tablet | 64px icon rail or collapsible 220px sidebar; 56px topbar; 20px inset | Collapsible rail with accessible labels/tooltips; no hidden destinations | Two-column cards; tables retain priority columns and detail expansion |
| 390px mobile | Single-column app; 56px compact topbar; 16px inset | 44px menu button opens full-height/width drawer; labeled links; focus returns to trigger | One-column sections; record cards; bottom-sheet filters; 44px controls |

Use `min-width: 0` on flex/grid content; allow long hostnames, MAC addresses and errors to wrap or expand. Do not fix app height in a way that blocks browser zoom or keyboard scrolling. Keep horizontal scrolling only for deliberate secondary-detail regions with a visible cue.

## Page-specific layout by viewport

| Page | 1440px | 768px | 390px |
|---|---|---|---|
| Overview | Four concise KPIs; trend/status; endpoint risks and activity; health rail | Two KPI columns; chart and lists stack into two-column/one-column flow | KPI pairs; trend simplified with summary; endpoint/activity cards; controls at top |
| Endpoint inventory | Search/filter row, compact summary, full server-paged table | Priority columns (name, connection, posture, time); IP/MAC in row detail | Endpoint cards; filter drawer; clear page count; no forced wide table |
| Endpoint detail | Identity/action header, evidence tabs and two-column evidence | Header wraps; scrollable tabs; one/two-column evidence based on minimum width | Stacked identity/actions; vertical timeline; long evidence disclosure; action confirmation |
| Compliance | Filters, summary, compliance table and category summary | Filters wrap; keep state and assessment timestamp in table | Cards with status/time; filters in drawer; one bulk action with confirmation |
| Hardware | Summary strip and table with details panel | Hide secondary scores until expansion | Hardware record cards with band, overall score, collection time and recommendation count |
| Jobs | Queue summary, filters, bounded/paged table and details panel | Show endpoint/type/status/age; attempt/error in expansion | Job cards and detail disclosure; refresh control explicit; no fake progress |
| Workers | Registry table and enrollment action | Name/state/last seen plus detail fields | Worker cards; token creation as dialog/sheet; revoke confirmation |
| Security indicators | Endpoint history list and finding detail | Severity/title/time, evidence collapsed | Finding cards; disclosure for evidence; “possible indicator” caveat retained |
| Reports | Current export controls and source/scope filters only | Group filters and export scope | Stepwise filter sheet; explicit export scope; no unsupported schedule controls |
| Users/roles | User table, role/status and admin actions | Hide secondary metadata in row detail | User cards and focused edit form; destructive actions confirmed |
| Policy/ISE settings | Split active config/history and rule scope | Stack editor/preview | One section at a time; review changes before submit |
| Applications/ports/warranty/audit | Wide server-paged tables with visible filter and count | Priority fields with detail expansion | Cards for rows; preserve endpoint link/state/time and page controls |
| Login | Centered bounded form | Same form with fluid width | Full-width card, large labeled inputs and reachable password visibility button |

## Interaction rules

- Filters preserve applied values and show active-filter count; mobile uses a sheet with Apply and Clear.
- Search input stays visible on endpoint and job inventory; global endpoint search remains available from the topbar/drawer.
- Pagination shows current range and total only when the API provides total. Do not report client loaded-row count as fleet total.
- Tables/cards retain state text and freshness on every viewport.
- Modals become near-fullscreen sheets on mobile; focus is trapped and restored.
- Charts reflow labels and preserve a text summary; no hover-only values.
- Toasts/errors stay within viewport and do not obscure persistent navigation or primary actions.

## Verification checklist for later implementation

At 1440/768/390 and 320px: no page-level horizontal overflow; no truncated critical status; usable filters/search; focus visible and logical; text remains readable at 200%; mobile actions meet touch target; tables have an equivalent mobile presentation; error/empty/stale states remain distinct; charts have accessible summaries; reduced-motion preference is honored.
