# Cross-project implementation roadmap

This roadmap is evidence-based planning only. Priority: P0 operational blocker, P1 readiness/contract, P2 product improvement, P3 scale validation or polish. Acceptance criteria are proposed; no implementation is authorized by this document.

## 1. Backend operational readiness

| Task | Priority | Dependencies | Acceptance criteria |
|---|---|---|---|
| Validate worker service under dedicated Windows identity | P0 | Approved WinSW v2.12.0 binary and hash; account/profile; administrator and disposable test host | SCM `StartName` matches the approved identity; service starts/stops and genuine restart is recorded; paths and ACLs verified; sanitized logs captured. |
| Prove DPAPI credential continuity | P0 | Dedicated identity/profile, secure enrollment process | Credential encrypted under actual service identity decrypts after real SCM restart; no secret printed/logged; outcome attached to PR6 evidence. |
| Prove SQLite outbox restart recovery | P0 | Service deployment and pending result on isolated data directory | Pending payload survives actual service/JVM restart; byte/hash matches; delivery completes once with receipt; WAL/SHM ACLs and recovery recorded. |
| Prove worker-to-backend delivery and lost-response recovery | P0 | Disposable backend, test proxy, worker credentials | Real worker submits; proxy drops first response after commit; identical replay receives matching receipt; backend has one accepted result and outbox is delivered; mismatched acknowledgement is rejected. |
| Close TLS, auth, secrets and route isolation review | P1 | Chosen deployment/proxy topology and security owner | Human, worker, API-key and ISE routes mapped to auth controls; secrets injected by approved store; TLS verified; direct app ports restricted; docs/metrics unreachable externally. |

## 2. Backend improvements supported by evidence

| Task | Priority | Dependencies | Acceptance criteria |
|---|---|---|---|
| Define a single worker/job result protocol and capability map | P1 | D-03 approval; protocol owner | Job type, script, payload schema, outcome and version compatibility are documented and centrally traceable; compatibility fixtures cover backend/Java/PowerShell boundaries. |
| Document and instrument job state transitions and recovery | P1 | D-04 approval; state-machine review | Transition table covers enqueue/claim/renew/complete/fail/expire/recover; fencing invariants are explicit; recovery events and queue age are observable. |
| Add worker fleet operational contract | P1 | D-01 approval; freshness and retention SLO | API/data contract exposes worker identity, shard, heartbeat freshness and delivery backlog without sensitive payload; authorization, retention and cardinality are defined. |
| Review migration/query/index design for fleet reads and job history | P1 | Expected filter/sort contracts; production-shaped query plans | Each high-volume query has documented bounds and measured plan; no unbounded fleet response in normal UI paths. |
| Define error classification and retry budget across worker/backend | P2 | Protocol and operational SLO decisions | Transient/permanent/conflict outcomes have clear bounded retry behavior, operator visibility, and idempotent receipt semantics. |

## 3. Worker deployment and PowerShell integration

| Task | Priority | Dependencies | Acceptance criteria |
|---|---|---|---|
| Complete deployment package verification on Windows | P0 | Operational readiness P0 tasks | WinSW config, account, ACL, stop timeout, restart behavior and package contents verified on clean host. |
| Validate each PowerShell collector as service identity | P0 | Service identity; representative endpoint permissions | All approved scripts run with expected permissions; output schema validates; partial/failure behavior and output caps are evidenced; secrets and sensitive payloads are absent from logs. |
| Establish package/version and script integrity procedure | P1 | Release ownership and signing/storage policy | Worker JAR and allowlisted scripts have release identity/hash; upgrade and rollback tested; version visible in operational telemetry. |
| Exercise outage backlog and disk recovery | P1 | SQLite proof; retry/outbox metrics; outage duration target | Outage at target duration keeps durable bounded backlog; disk thresholds alert; restart drains safely without duplicate results or deleting unresolved items. |

## 4. Frontend design and implementation

| Task | Priority | Dependencies | Acceptance criteria |
|---|---|---|---|
| Approve product design direction and tokens | P2 | Review `docs/frontend/DESIGN.md` | Contrast tested in both themes; semantic status mapping accepted; responsive breakpoints and type scale documented. |
| Separate auth/session readiness from ISE health | P1 | D-05 decision; auth behavior clarified | Valid session remains usable during ISE outage; 401 alone triggers auth flow; ISE degradation remains visible with freshness. |
| Standardize loading/error/empty/stale and table semantics | P2 | API page contracts; product design | Pages distinguish failures from empty datasets; accessible announcements; exports clearly state scope; server/client pagination is explicit. |
| Add worker operations and delivery views | P1 | D-01 and result-delivery contract | Page shows per-worker state and delivery freshness from approved API; never labels local backend pool as worker fleet health. |
| Improve existing endpoint, compliance, job and detail views | P2 | Server paging/filter contract; design review | Queries are bounded; detail fan-out and partial-data states are measured; filter, sort, freshness, evidence and responsive behavior meet page plan. |
| Complete accessibility and responsive review | P2 | Implemented UI and test environment | Keyboard, screen reader, 200% zoom, 320/768/1280 widths, reduced motion, contrast, dialog focus and chart alternatives pass documented review. |

## 5. Backend/frontend integration

| Task | Priority | Dependencies | Acceptance criteria |
|---|---|---|---|
| Publish a route/DTO contract map and compatibility policy | P1 | D-02/D-03 approval | Each UI page maps to controller route and DTO; required/optional fields and error codes documented; worker protocol compatibility versioned. |
| Implement job paging and fleet filters before claiming completeness | P1 | D-02 approval, DB query plan | UI can query by approved status/type/time/endpoint criteria, stable page order, and bounded page size; load does not depend on fetching all jobs/endpoints. |
| Add end-to-end browser/API checks for critical workflows | P2 | Operationally available environment and approval to run checks | Login, endpoint search/detail, job enqueue/progress, result delivery and degraded ISE states have deterministic acceptance evidence. |

## 6. Terraform and AWS infrastructure

| Task | Priority | Dependencies | Acceptance criteria |
|---|---|---|---|
| Decide target AWS topology and trust boundaries | P1 | D-07/D-09 decisions; compliance, cost and identity owners | Architecture states backend compute, Windows worker hosting/connectivity, PostgreSQL, ingress, egress, secrets, logging, backup, recovery and environment separation. |
| Author Terraform plan and threat/cost review | P2 | Approved topology, AWS account/region | IaC is reviewed, least privilege and secret references are checked, state is protected, environments are isolated, and deployment/rollback are repeatable. |
| Verify backup, restore and disaster recovery | P1 | Database topology and RPO/RTO decision | Restore drill meets approved RPO/RTO; backup encryption and access logs verified; worker outbox and database receipt recovery behavior documented. |

## 7. Load testing and 50,000-endpoint validation

| Task | Priority | Dependencies | Acceptance criteria |
|---|---|---|---|
| Establish workload model and SLOs | P1 | D-08 decision | Define endpoint count, collection frequency, result size, peak concurrency, outage backlog, freshness objective, job latency, and RPO/RTO. |
| Build production-shaped 50k dataset and baseline query profile | P2 | Safe isolated environment and approved fixture generation | Dataset covers endpoint/evidence/job age distribution; fleet/dashboard/detail query plans and response sizes recorded without production data. |
| Load test concurrent claims, leases and results | P2 | Worker protocol and isolated DB | Peak plus agreed headroom meets job latency and delivery SLO; no stale-worker mutation, duplicate evidence, receipt conflict loss, or pool starvation. |
| Load test frontend fleet and job workflows | P2 | Server-paged contracts and UI implementation | 50k search/filter/page and job monitoring stay within agreed latency/network budgets; bounded query counts and no N+1 fleet requests. |
| Test prolonged backend outage, recovery and retention | P2 | Worker outbox telemetry and retention policy | Backlog stays within disk budget, recovery drains at required rate, evidence/receipts remain consistent, retention does not remove unresolved lifecycle evidence. |
