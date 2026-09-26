# VE Compliance Engine — The Complete Backend Explainer
### Every folder. Every file. Every class. Every method. Every annotation. What it is, why it exists, how it works, and what it does.

---

# PART 0 — Why Any Of This Exists

Before touching a single file, it's worth answering the question nobody usually writes down: **why does this project need a backend at all, and why does it look like this?**

## 0.1 The problem being solved

The platform needs to answer one question, safely, over and over, for potentially tens of thousands of machines: *"Is this Windows laptop healthy and compliant right now, and should Cisco ISE let it stay on the network?"*

That single question breaks into sub-problems the moment you try to build it for real:
- Somebody has to **discover** which devices exist (Cisco ISE already knows — poll it).
- Somebody has to **run a check** against a device without installing software on it (agentless — PowerShell over WinRM/CIM).
- The check takes time and can fail (network down, wrong credentials, WinRM off) — so it can't happen synchronously inside an HTTP request. It needs a **queue**.
- The result has to be **stored permanently** — not just "the current status," but the full history, because "was this device ever non-compliant, and when did it get fixed" is a real question auditors ask.
- A **human**, not a program, must decide whether to act on that result — because automatically quarantining someone's laptop because a background script misfired is how you get fired.
- Every action taken against the network (quarantine, clear, share) must be **audited** — logged permanently, success or failure, so there's a paper trail.

Every backend package you're about to read maps directly onto one of those bullet points. Nothing in this codebase is decorative.

## 0.2 Why it was rewritten from Python/Flask to Java/Spring Boot

The original prototype (`posture_app.py`, `posture_ui.py`, `ise_session_watcher.py`) worked, but it leaned on mechanisms that don't survive contact with scale or a second developer:
- **Flat files as a queue** (`pending_devices.txt`, `seen_macs.txt`) — coordinated with Windows-only file locking (`msvcrt`). This cannot run on more than one worker process safely, has no transaction semantics, and leaves no audit trail of its own state.
- **SQLite-flavored SQL hand-shimmed onto Postgres** — `posture_db.py` existed specifically to regex-translate `?` placeholders into `%s` and simulate SQLite behavior. Every SQLite-only construct (`AUTOINCREMENT`, `INSERT OR IGNORE`, `datetime('now', ?)`) was a landmine that only went off when that code path finally ran for the first time (see the bug table in your own progress notes — seven of these were found one at a time).
- **No real foreign keys** — the Python schema related tables by MAC address as a plain string. A typo'd MAC silently created an orphaned row; Postgres never objected because nothing told it to.
- **No authentication at all** on the dashboard.

None of these are "Python's fault" — they're what happens when a schema and a queue grow organically without a framework enforcing structure. The Java rewrite's entire value proposition is: **make the structural rules impossible to violate by accident**, using tools whose whole job is enforcing exactly that (a real relational schema with foreign keys, a real transactional queue table, a real auth framework). That is the lens to read every choice below through.

---

# PART 1 — What A Database Actually Is, And Why This Project Needs One

Skip this section if you already have this cold — but since you asked for "anybody" to understand it, here it is properly.

## 1.1 What a relational database is

A relational database (PostgreSQL, in this case) stores data as **tables** — rows and columns, like a spreadsheet, except:
- Every table has a declared **schema**: fixed columns, with fixed types (`UUID`, `TEXT`, `TIMESTAMPTZ`, `BOOLEAN`, `JSONB`...). You cannot silently put a string where a number is expected.
- Tables can reference each other through **foreign keys** — a column in one table that must match a real row's primary key in another table. This is what "relational" means: `assessment.endpoint_id` must point at a row that genuinely exists in `endpoint`. Try to insert an assessment for an endpoint ID that doesn't exist, and Postgres refuses the write outright. This is exactly the class of bug ("MAC-string logical relationships only") the Python prototype couldn't catch.
- Every write happens inside a **transaction**: a group of changes that either *all* succeed or *all* roll back together. If saving an assessment plus its check results plus updating the endpoint's `last_seen_at` were three separate uncoordinated writes and the process crashed between step 2 and 3, you'd have corrupted, half-written data. A transaction makes "assessment + all its checks" atomic — it's one unit or nothing.
- Databases guarantee **ACID**: Atomicity (all-or-nothing transactions), Consistency (constraints like foreign keys and `CHECK` clauses are never violated), Isolation (two concurrent operations don't see each other's half-finished work), Durability (once committed, a crash can't lose it).

## 1.2 Why *this* project specifically needs one

- **Append-only evidence.** The whole platform's principle is `OBSERVATION → EVIDENCE → HUMAN REVIEW → OPTIONAL ISE ACTION`. "Evidence" only means something if it's durable and never silently overwritten — which is exactly what a database row, once committed, is.
- **Concurrency safety for the job queue.** Multiple things could try to claim the same "check this device" job at once (a restart racing an in-flight run, in theory multiple workers later). A plain in-memory queue or flat file has no way to guarantee only one claimant wins; a database row lock (`SELECT ... FOR UPDATE SKIP LOCKED`, explained in Part 4) does, by design.
- **Structured + semi-structured data together.** Some data is rigidly structured (an endpoint's MAC address, a job's status) — that's a normal typed column. Some data's *shape* varies and evolves (a PowerShell agent's raw hardware report) — that's what the `JSONB` columns are for (Part 1.3).
- **Queryability over time.** "Show me every endpoint that was non-compliant in the last 7 days" is a `WHERE` clause with an index behind it, not a script that reads every file on disk.

## 1.3 What JSONB specifically buys you

A normal column has one fixed shape. `JSONB` (binary JSON) lets one column hold a whole nested object — and Postgres still lets you *index into it* and query specific fields inside it (`GIN` indexes), without needing a schema migration every time the PowerShell agent's report shape changes slightly. This project uses it for exactly that: `check_result.details`, `hardware_health.raw_report` — because the exact fields a Windows box reports (disk health, battery wear, which firewall profile is off) are inherently variable, but you still want to query into them later.

## 1.4 Why Flyway, specifically, on top of "just having a database"

A schema isn't static — it grows as features are added (`V1` through `V11` in this project). Flyway turns every schema change into a **version-controlled, numbered SQL file**, applied exactly once, in order, tracked in Flyway's own bookkeeping table. The alternative — hand-running `ALTER TABLE` statements against a live database, or idempotent `CREATE TABLE IF NOT EXISTS` scattered through app startup code (the Python prototype's approach) — means nobody can be 100% sure what shape the schema is actually in on any given machine. Flyway removes that ambiguity: the migration files *are* the schema's source of truth, and any fresh database (your laptop, CI, a teammate's machine) ends up bit-for-bit identical by replaying the same numbered steps.

---

# PART 2 — Repository Layout (The Map)

```
backend/
├── pom.xml                                   Maven build definition
├── run.ps1                                   Manual end-to-end test script
└── src/main/
    ├── resources/
    │   ├── application.yml                   All runtime configuration
    │   └── db/migration/
    │       ├── V1__create_users_and_roles.sql
    │       ├── V2__create_endpoints.sql
    │       ├── V3__create_posture_jobs.sql
    │       ├── V4__create_assessments.sql
    │       ├── V8__create_hardware_health.sql
    │       ├── V9__add_hardware_health_failure_tracking.sql
    │       ├── V10__create_endpoint_session_log.sql
    │       └── V11__create_ise_action_audit.sql
    └── java/com/endpointposture/
        ├── EndpointPostureApplication.java   The entry point
        ├── config/                           App-wide, cross-cutting config
        ├── security/                         Auth: users, JWT, filters
        ├── endpoint/                         The core "device" entity
        ├── job/                              The Postgres-backed work queue
        ├── posture/                          Firewall/ports/apps evidence
        ├── hardware/                         CPU/memory/storage/battery evidence
        ├── ise/                              Cisco ISE integration + enforcement
        ├── session/                          ISE connect/disconnect polling
        └── audit/                            The permanent ISE-action log
```

Each package below follows the same internal shape where it applies: an **entity** (maps to a table), a **repository** (data access), a **service** (business logic — the only place that's allowed to touch the repository for writes), a **controller** (HTTP layer), and a **dto/** folder (the shapes that cross the HTTP boundary, kept deliberately separate from the entities that map to the database).

---

# PART 3 — The Entry Point

## `EndpointPostureApplication.java`

**What it is:** the `public static void main(String[] args)` — the single file Java actually runs when the JAR starts.

**Why it's shaped this way:**
- `@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)` — `@SpringBootApplication` is itself shorthand for three annotations bundled together: `@Configuration` (this class can define beans), `@EnableAutoConfiguration` (Spring Boot should guess and wire up sensible defaults for whatever's on the classpath — a database driver present means "configure a DataSource," etc.), and `@ComponentScan` (find every `@Component`/`@Service`/`@RestController`/`@Repository` under this package and register them). The `exclude` stops Spring Security's *default* behavior of inventing an in-memory user with a random generated password printed to the console at boot — this app has real users in the `app_user` table instead, so that default would just be noise (and a security footgun if left on).
- `@EnableScheduling` — without this one line, every `@Scheduled` method in the codebase (`JobWorker.pollAndRun()`, `IseSessionWatcher.tick()`) would simply never fire. This annotation turns on Spring's internal timer/thread-pool machinery that calls scheduled methods on their configured interval.
- `@EnableConfigurationProperties({PostureAgentProperties.class, HardwareAgentProperties.class, IseProperties.class})` — registers three plain Java classes (Part 5.x, 7.x, 8.x) as targets that Spring should populate directly from `application.yml`, so `app.posture.script-path: ...` in YAML becomes `postureAgentProperties.getScriptPath()` in Java, type-checked, with no manual `@Value` string-parsing per field.

**Effect:** running `java -jar app.jar` (or `mvn spring-boot:run`) boots an embedded web server on port 8090, connects to Postgres, runs any pending Flyway migrations, starts the two scheduled background loops, and starts accepting HTTP requests — all from this one file's `main` method calling `SpringApplication.run(...)`.

---

# PART 4 — `job/` — The Postgres-Backed Work Queue

**Why this package exists at all:** a posture check can take tens of seconds (WinRM round-trips) and can fail in many ways. You cannot run it inline inside an HTTP request/response cycle. So work gets *enqueued* as a row, and a background loop *claims and executes* rows one at a time. This package **is** the queue — it fully replaces the Python prototype's `pending_devices.txt` flat file.

## `JobType.java`
**What it is:** a Java `enum` — a fixed, closed set of named constants (`POSTURE_CHECK`, `HARDWARE_CHECK`).
**Why an enum and not a plain string:** the compiler enforces that only these two values can ever exist in Java code — you cannot typo `"POSTURE_CEHCK"` and have it silently compile. Mapped to the database with `@Enumerated(EnumType.STRING)`, so the column itself stores readable text (`'POSTURE_CHECK'`), not an opaque integer — readable directly in Adminer/psql without a lookup table.
**Effect:** every job in the system is unambiguously one of exactly two kinds of work.

## `JobStatus.java`
**What it is:** an enum: `QUEUED → RUNNING → COMPLETE`, or `QUEUED → RUNNING → QUEUED (retry) → ... → FAILED`.
**Why it matters:** this is the entire state machine for a job's life, expressed as data rather than scattered `if` statements. A job's current status is always exactly one of these four words, queryable directly (`WHERE status = 'QUEUED'`).

## `PostureJob.java`
**What it is:** the `@Entity` — the Java class that maps 1:1 onto the `posture_job` table.

Field-by-field, what each annotation means:
- `@Id @GeneratedValue private UUID id;` — `@Id` marks this as the table's primary key; `@GeneratedValue` tells Hibernate "don't set this yourself, let the database generate it" (the SQL default is `gen_random_uuid()`).
- `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "endpoint_id", nullable = false) private Endpoint endpoint;` — this is a **foreign key relationship**, mapped as an object reference instead of a raw UUID. `@ManyToOne` means "many jobs can point at one endpoint." `fetch = FetchType.LAZY` means Hibernate does *not* load the full `Endpoint` row when it loads a `PostureJob` — it only fetches it the moment code actually calls `.getEndpoint()`. This avoids wastefully loading data nobody asked for, but (see `JobService` below) it has a real, documented gotcha.
- `@Enumerated(EnumType.STRING) private JobType jobType;` / `private JobStatus status;` — as above, stored as readable text.
- `private int priority;` — higher values get claimed first (used by the claim query in `PostureJobRepository`).
- `private int attemptCount;` / `private int maxAttempts;` — how many times a worker has tried this job, and the ceiling before it's abandoned to `FAILED`.
- `private String errorMessage;` — the reason for the most recent failure, kept for humans to read.
- `@Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;` — `updatable = false` tells Hibernate to never include this column in an `UPDATE` statement even if the Java field somehow changed — the creation time is permanent.
- `@PrePersist void onCreate() { ... }` — a **JPA lifecycle callback**: this method runs automatically, right before the very first `INSERT` for this entity. Here it fills in sane defaults (`createdAt = Instant.now()`, `status = QUEUED`, `maxAttempts = 3`) so calling code doesn't have to remember to set them every time a job is built.
- `@Builder` / `@Getter` / `@Setter` / `@NoArgsConstructor` / `@AllArgsConstructor` (Lombok) — generates the fluent builder, getters/setters, and both constructor forms at compile time, so none of that boilerplate is hand-written.

**Effect:** one Java object, `PostureJob`, *is* one row in `posture_job` — reading the object reads the row, saving the object writes the row, and the annotations are the entire mapping definition; no separate XML or SQL-mapping file needed.

## `PostureJobRepository.java`
**What it is:** an interface extending `JpaRepository<PostureJob, UUID>` — and this is where Spring Data's "magic" is most visible.

- `JpaRepository<PostureJob, UUID>` — by extending this with no method bodies at all, you already get `save()`, `findById()`, `findAll()`, `deleteById()`, etc., for free. Spring generates a real implementation class at startup by inspecting the generic type parameters.
- **`findNextClaimable()`** — this is the single most important query in the whole backend:
  ```sql
  SELECT * FROM posture_job
  WHERE status = 'QUEUED'
    AND (next_attempt_at IS NULL OR next_attempt_at <= now())
  ORDER BY priority DESC, created_at ASC
  LIMIT 1
  FOR UPDATE SKIP LOCKED
  ```
  **What is `FOR UPDATE SKIP LOCKED`, and why does it matter?** `FOR UPDATE` tells Postgres "lock whatever row(s) this query returns, for the rest of this transaction — nobody else can touch them." `SKIP LOCKED` tells it "if a row *would* match but is already locked by another transaction, silently skip it instead of waiting." Put together: if two workers ran this query at the exact same millisecond, they are *guaranteed* to get two different rows (or one gets nothing) — never the same row twice. This single database clause is the entire concurrency-safety mechanism for the whole job system; it's what made the flat-file locking in the Python prototype (`msvcrt`, Windows-only, single-process) obsolete.
  It's marked `@Query(value = "...", nativeQuery = true)` because `FOR UPDATE SKIP LOCKED` is Postgres-specific SQL, not something JPA's abstract query language (JPQL) knows how to express.
- **`findAllByOrderByCreatedAtDesc()`** — note the `JOIN FETCH j.endpoint` in its `@Query`. This is a deliberate fix for the lazy-loading gotcha above: since this method's results are meant to be read (including `.getEndpoint()`) *after* the request that called it, the join is done eagerly, inside the query itself, so there's no lazy-proxy trap waiting later.
- **`findByEndpoint_IdOrderByCreatedAtDesc(UUID endpointId)`** — this method name alone (no query needed) is parsed by Spring Data into SQL: `findBy` + `Endpoint_Id` (traverse the `endpoint` relationship, then its `id` field) + `OrderByCreatedAtDesc`. This is **derived query generation** — you describe the query in the method's name using a small fixed grammar, and Spring builds the SQL from it.

## `JobService.java`
**What it is:** the business-logic layer — the *only* class allowed to change a job's state. Controllers never touch the repository directly for anything beyond simple reads; this pattern (controller → service → repository) keeps validation and business rules in one place instead of duplicated across every caller.

- **`enqueue(UUID endpointId, JobType type, int priority)`** — looks up the endpoint (throwing `EndpointNotFoundException` if it doesn't exist — you cannot queue work for a device that isn't real), builds a `PostureJob` with `status = QUEUED`, saves it. `@Transactional` wraps the whole method: if anything after the endpoint lookup throws, nothing is committed.
- **`claimNextJob()`** — calls the `FOR UPDATE SKIP LOCKED` query above, and if it got a row, immediately flips it to `RUNNING`, stamps `startedAt`, increments `attemptCount`, and saves — *all inside the same transaction as the claim itself*. This atomicity is what makes the claim safe: between "I found this job" and "I marked it mine," no other transaction can see it as still `QUEUED`. The method also contains this exact, deliberately-commented line:
  ```java
  job.getEndpoint().getMacAddress();
  ```
  **What is this line actually for?** It looks pointless — the return value is discarded. But it *forces* Hibernate to resolve the lazy `Endpoint` proxy right now, while the transaction (and Hibernate's session) is still open. `JobWorker` reads `job.getEndpoint()` *after* this method returns, by which point the transaction has closed — without this line, that later read would throw `LazyInitializationException`. This is a real bug this codebase hit once (`JobController`'s earlier version) and fixed by adding exactly this kind of "touch it now" line wherever a lazy relationship needs to survive past the transaction boundary.
- **`markComplete(UUID jobId)`** — flips status to `COMPLETE`, clears any old error message.
- **`markFailed(UUID jobId, String errorMessage)`** — the retry/backoff logic: if `attemptCount < maxAttempts`, it goes back to `QUEUED` with `nextAttemptAt` pushed into the future by `min(attemptCount, 15)` minutes (an ever-growing but capped delay — "exponential-ish backoff," so a genuinely broken target isn't hammered every 3 seconds). Once attempts are exhausted, it's set to `FAILED` permanently, for a human to notice.
- **`enqueueIfDue(UUID endpointId, JobType type)`** — used by `IseSessionWatcher` on every reconnect. Without this guard, a device flapping connected/disconnected every 15-second poll tick would spawn a new job every single tick. This method checks "is there already a `QUEUED` or `RUNNING` job of this type for this endpoint?" and only enqueues if not.

**Effect of this whole file:** every state transition a job can ever go through happens through exactly one of these methods, each wrapped in its own transaction — so the job table's state is always internally consistent, never half-updated.

## `JobWorker.java`
**What it is:** the class that turns a queued row into an actual running PowerShell process. This is the direct successor to the Python prototype's `auto_worker()` thread.

- `@Scheduled(fixedDelayString = "${app.jobs.poll-interval-ms:3000}")` on `pollAndRun()` — every 3 seconds (configurable, default from `application.yml`), this method runs. `fixedDelay` (as opposed to `fixedRate`) specifically means "wait N ms *after this run finishes* before starting the next one" — so a single worker instance can never overlap itself, even if one run happens to take longer than the interval.
- **`dispatch(PostureJob job)`** — resolves which agent script to run based on `job.getJobType()` using a Java `switch` expression (`switch (job.getJobType()) { case POSTURE_CHECK -> ...; case HARDWARE_CHECK -> ...; }` — the modern arrow-style switch, which also *must* be exhaustive: if a third `JobType` were ever added and this switch weren't updated, it wouldn't compile). Wrapped in try/catch so a dispatch failure is recorded as a job failure instead of crashing the scheduler thread (which would silently kill *all future* scheduled ticks — a subtle but serious failure mode `@Scheduled` methods must always guard against).
- **`runPostureAgent` / `runHardwareAgent`** — build the exact `powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File ...` command line, with every timeout value pulled from `PostureAgentProperties`/`HardwareAgentProperties` (never hardcoded), and the API key passed through the **process environment**, not the command line — command-line arguments are visible to any other process on the machine inspecting the process list, environment variables passed this way are not.
- **`runProcess(...)`** — starts the process with `ProcessBuilder`, and runs a **separate daemon thread** just to drain the child process's stdout as it streams, into a `StringBuffer` (thread-safe, unlike `StringBuilder`, because one thread writes while another later reads it). *Why a separate thread for this specifically?* A child process's stdout pipe has a fixed OS buffer size; if nobody reads it while the parent is blocked waiting for the process to exit, and the process produces enough output, the pipe fills and the child process itself blocks trying to write to it — a classic deadlock. Draining on its own thread prevents that entirely.
  It then calls `process.waitFor(timeoutSeconds, TimeUnit.SECONDS)` — if the process doesn't finish within the configured outer timeout, `process.destroyForcibly()` kills it. This is the documented "outer backstop" behind each agent script's own shorter internal CIM/WinRM timeouts — the exact fix for a real, previously-hit bug where a hung-but-listening WinRM service could outlast a shorter subprocess timeout and produce a confusing, contentless error.
- **`extractResultJson(String output)`** — scans every line of the agent's console output for one starting with `RESULT_JSON:`, and parses the JSON after that prefix. Agents are contracted to always print exactly this line, so the worker has a machine-readable outcome even when the HTTP submission the agent tried to make failed outright.
- **`handleResult(...)`** — decides success/failure purely from that parsed JSON's `submitted` boolean. If `submitted: true`, nothing more is written here — the real evidence row was already saved by the ingestion HTTP endpoint the agent itself called; this method just marks the job `COMPLETE`. If it's missing or `false`, `fail(...)` is called.
- **`fail(PostureJob job, Endpoint endpoint, String reason)`** — this is the "a failed attempt is still evidence" principle made concrete in code: it writes a *permanent failure row* (an `ERROR` assessment, or a `succeeded = false` hardware-health row) **before** calling `jobService.markFailed(...)`. Losing the ability to check a device is itself worth recording, not silently dropping.

**Effect:** this one class is the bridge between "a row exists in Postgres saying work needs doing" and "a real PowerShell process actually ran against a real Windows machine and its result got recorded" — every single time, with a hard timeout backstop and permanent evidence either way.

## `JobController.java` / `dto/JobResponse.java`
**What they are:** the HTTP-facing layer for the queue — `POST /api/v1/jobs` to enqueue manually (used for testing the pipeline before automatic ISE-triggered enqueueing was wired up), `GET /api/v1/jobs` and `GET /api/v1/jobs/endpoint/{id}` to list.
**Why a separate `JobResponse` record instead of returning the `PostureJob` entity directly:** returning the entity would leak internal database structure (and risk that exact lazy-loading trap) straight onto the wire, and couples your API's shape to your table's shape forever. `JobResponse` is a flat, deliberately-shaped snapshot — `toResponse(PostureJob job)` in the controller does the conversion by hand, pulling exactly the fields a caller needs (including flattening `job.getEndpoint().getMacAddress()` into a plain `macAddress` string) and nothing more.

---

# PART 5 — `endpoint/` — The Core Device Entity

**Why this package exists:** every other package in the system ultimately points *at* a device. This package owns "what is a device, and how do we find/create one by its real-world identity (MAC address)."

## `Endpoint.java`
**What it is:** the `@Entity` for the `endpoint` table — the row that represents one physical device.

Key design points, each deliberate:
- `@Column(name = "mac_address", nullable = false, unique = true) private String macAddress;` — the `unique = true` constraint is enforced *by the database itself*, not just application code — two rows can never share a MAC, no matter how many code paths try to insert one.
- `private boolean connected;` plus `sessionStartedAt` / `lastDisconnectedAt` — tracked **completely independently** of anything posture-related. This is a documented architectural rule: a device can be connected-with-stale-posture, or disconnected-with-good-history, and the UI/data model must never conflate the two. That's why "is this device online" lives on the `Endpoint` row itself, while "was its last check compliant" lives entirely in a separate table (`assessment`) queried separately.
- `@PrePersist void onCreate()` / `@PreUpdate void onUpdate()` — two lifecycle hooks: one fills `firstSeenAt`/`lastSeenAt`/`createdAt`/`updatedAt` on first insert, the other refreshes `updatedAt` on every single subsequent save automatically — no calling code anywhere has to remember to bump a timestamp by hand.

## `EndpointRepository.java`
- `findByMacAddress(String macAddress)` — the real lookup path every ingestion route uses.
- `findAllByConnectedTrue()` — used by `IseSessionWatcher` to find devices that need checking for having *dropped off* ISE's active list since the last poll.

## `EndpointService.java`
**What it is:** the single write-path for endpoints — every collector (posture agent submissions, the ISE session watcher) goes through this, never a bare `repository.save()` from anywhere else, which is exactly what makes "MAC uniqueness is the single source of truth for identity" actually true in practice rather than just in intent.

- **`normalizeMac(String mac)`** — `static`, so it's a pure utility, callable without an instance. Converts `aa-bb-cc-dd-ee-ff` (how Windows often prints a MAC) and `AA:BB:CC:DD:EE:FF` (how Cisco ISE prints it) into one canonical form (`.trim().replace('-', ':').toUpperCase(...)`). **Why this specific method exists:** without it, the exact same physical device reported by two different sources with two different separator styles would silently create *two different rows* — a real, subtle data-integrity bug this method exists purely to prevent.
- **`upsertByMac(...)`** — "upsert" = update-if-exists, insert-if-not. Looks the endpoint up by normalized MAC; if found, updates only the fields that were actually passed in (a `null` argument explicitly means "no new information, leave the stored value alone" — documented directly in the Javadoc); if not found, builds a brand-new `Endpoint`. This is the core ingestion entry point every posture/hardware report goes through.
- **`updateHardware(...)`** — same null-means-leave-alone pattern, scoped to just manufacturer/model/serial.
- **`markConnected(String macAddress, String ip)`** / **`markDisconnected(String macAddress)`** — called only by `IseSessionWatcher`. Note `markConnected` only sets `sessionStartedAt` on the actual disconnected→connected *transition* (`if (!wasConnected)`), not on every 15-second poll that finds the device still connected — and it writes one `EndpointSessionLog` row on that same transition, so connect/disconnect history is a genuine event log, not a snapshot.

## `EndpointController.java` / `dto/EndpointResponse.java` / `EndpointNotFoundException.java`
- Two read-only routes: `GET /api/v1/endpoints` (list all) and `GET /api/v1/endpoints/{id}` (one, by internal UUID — deliberately *not* by MAC; the UUID is the stable external API reference, the MAC is the internal business key used only for ingestion/matching).
- `EndpointResponse` is a `record` with `@Schema(description = "...")` annotations on every field — these feed directly into the Swagger UI documentation, so the auto-generated API docs explain *why* a field exists (e.g. `connected`'s description literally states the independent-from-posture rule above), not just its type.
- `EndpointNotFoundException` — `@ResponseStatus(HttpStatus.NOT_FOUND)` is a one-line trick: throw this exception anywhere in the app, and Spring automatically turns it into an HTTP `404` response with no manual `try/catch` in the controller needed.

## `dto/EndpointSummaryResponse.java` (and the root-level copy)
**What it is (currently):** an empty file. **Why it's mentioned here at all:** it's a placeholder for a planned dashboard-summary DTO that was never actually written — not a bug, just unfinished scaffolding. Worth knowing it exists so it isn't mistaken for a missing feature elsewhere.

---

# PART 6 — `posture/` — Firewall / Ports / Applications Evidence

**Why this package exists:** this is the actual "evidence" half of `OBSERVATION → EVIDENCE`. Every time the PowerShell posture agent finishes checking a device, this package is what turns that report into permanent database rows.

## `AssessmentStatus.java`
An enum: `COMPLIANT`, `NON_COMPLIANT`, `ERROR` — used at **two levels at once**: the overall result of a whole assessment, and the result of each individual check inside it. The Javadoc explicitly warns: **declaration order matters**. `PostureIngestService.overallStatus()` picks the single worst status across all checks using `check.status().ordinal() > worst.ordinal()` — `ordinal()` is each enum constant's position in its declaration (COMPLIANT=0, NON_COMPLIANT=1, ERROR=2), so this only works correctly because they're declared least-to-most-severe. Reordering the enum would silently break this comparison — a sharp, documented gotcha.

## `Assessment.java` / `CheckResult.java`
**What they are:** the two entities behind `assessment` and `check_result` — a **one-to-many** relationship (one assessment, many checks), but modeled here as plain foreign-key UUID columns (`assessment_id` on `CheckResult`) rather than a JPA `@OneToMany` object graph. **Why that choice:** it keeps writes simple and avoids cascade/lazy-loading surprises — the same reasoning documented for `PostureJob.endpoint`, applied deliberately in the opposite direction here to avoid needing another lazy-load workaround.
- `CheckResult.details` — `@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb")` — this is Hibernate 6's *native* JSONB mapping (no extra library dependency needed), letting a `Map<String, Object>` in Java round-trip transparently to/from a real `jsonb` column. This is where each check's raw evidence (which firewall profiles were off, which ports were open vs blocked) actually lives.
- Both entities are **append-only by design** — nothing in this codebase ever calls `UPDATE` on an existing assessment or check row. New data = new row. That's what makes "assessment history" a real, queryable thing rather than a single mutable "current status" field.

## `AssessmentRepository.java` / `CheckResultRepository.java`
Standard Spring Data derived-query repositories: `findByEndpointIdOrderByCreatedAtDesc`, `findFirstByEndpointIdOrderByCreatedAtDesc` (the "latest" query — `findFirst...` combined with `OrderBy...Desc` is exactly "give me one, the newest"), `findByAssessmentId`.

## `AssessmentService.java`
**The single write path for posture evidence.** Two entry points into it, both worth understanding:
- **`recordAssessment(...)`** — called once a finished result exists. Saves the `Assessment` row first (to get its generated `id`), then loops over the checks, saving each `CheckResult` tagged with that `assessmentId`. **No ISE call happens anywhere in this method** — stated explicitly in the Javadoc as deliberate, not an oversight, because this is precisely the boundary the whole platform's non-negotiable architecture rule depends on.
- **`recordFailure(UUID endpointId, UUID jobId, String detail)`** — called by `JobWorker` when a check never even produced a result. Writes an `ERROR`-status assessment with **zero checks** — a deliberately minimal but real row, because "we tried and couldn't" is itself a fact worth keeping, not a reason to write nothing.

## `PostureIngestService.java`
**What it is:** the layer *between* the raw HTTP request body and `AssessmentService` — where the incoming report is turned into what `AssessmentService` needs.
- **`ingest(PostureReportRequest req)`** — first calls `EndpointService.upsertByMac(...)` (creating the device if this is the very first time it's ever been seen), then optionally updates hardware identity, then calls `AssessmentService.recordAssessment(...)`.
- **`overallStatus(...)`** — the ordinal-comparison logic described above, ensuring the assessment's own overall status can never look *better* than its worst individual check — a real bug class ("dashboard says compliant, but one check actually errored") this method exists specifically to prevent.
- **`summarize(...)`** — builds a one-line, human-readable string ("`FIREWALL: Disabled: Public | APPLICATIONS: Missing required: Cisco Secure Client`") from whichever checks weren't compliant, for the audit/UI layer to show without needing to re-parse JSON.

## `PostureIngestController.java` / `PostureQueryController.java`
**Why these are two entirely separate classes, not one:** this is the concrete, structural enforcement of the platform's core rule. `PostureIngestController` only exposes `POST /api/v1/posture` and only ever calls `PostureIngestService`. `PostureQueryController` only exposes the `GET` routes and only ever calls `AssessmentService`'s read methods. Because they're different classes wired to different service beans, there is no code path by which "a posture report arrived" could ever fall through into "call ISE" — the separation isn't a comment, it's the actual Java class boundary.

## `AssessmentNotFoundException.java`
Same `@ResponseStatus(HttpStatus.NOT_FOUND)` pattern as `EndpointNotFoundException` — thrown when `getLatestForEndpoint` finds nothing, auto-converted to a clean `404`.

## `config/PostureAgentProperties.java`
**What it is:** a plain Java class annotated `@ConfigurationProperties(prefix = "app.posture")` — every `app.posture.*` key in `application.yml` (script path, server URL, API key, three separate timeout values) is bound directly onto typed fields here (`scriptPath`, `serverUrl`, `apiKey`, `cimTimeoutSeconds`, `serverTimeoutSeconds`, `processTimeoutSeconds`), with getters/setters. **Why bother with a whole class instead of scattered `@Value("${app.posture.script-path}")` annotations:** type safety and one obvious place to see every posture-agent setting at once, plus the Javadoc directly documents the *relationship* between the values ("the timeouts are layered on purpose: the outer process timeout must exceed the inner CIM/server timeouts with headroom") — a fact that would be easy to lose if the values were scattered across many files.

## `dto/PostureReportRequest.java` / `dto/CheckInput.java` / `dto/AssessmentResponse.java` / `dto/CheckResultResponse.java`
**What DTOs are, generally, and why this project has so many of them:** a DTO (Data Transfer Object) is a class whose *only* job is to define the exact shape of data crossing a boundary — here, specifically, the HTTP request/response bodies. They're kept deliberately separate from the `@Entity` classes (`Assessment`, `CheckResult`) that map to the database, for a real reason: an entity's shape is dictated by the schema and JPA's needs (lazy proxies, internal IDs); an API's shape should be whatever's actually useful to a caller, and shouldn't have to change every time the database schema does (or vice versa). `PostureReportRequest` uses Bean Validation annotations (`@NotBlank macAddress`, `@NotNull status`) — when `@Valid` is present on a controller parameter (as it is in `PostureIngestController.ingest`), Spring automatically rejects a malformed request with a `400 Bad Request` *before* your method body even runs, with zero manual `if` checks written.

---

# PART 7 — `hardware/` — CPU / Memory / Storage / Battery Evidence

**Why this exists as a separate package from `posture/`, not folded into it:** it's a genuinely different kind of evidence (telemetry/health scoring vs. compliance pass/fail), collected by a different PowerShell agent, on a different cadence, and — crucially — it has its own scoring math that posture checks don't need at all.

## `HardwareBand.java`
An enum: `HEALTHY, WARNING, DEGRADED, CRITICAL`. The thresholds that decide which band a score falls into (85+/70+/50+/below) are explicitly documented as **illustrative, not confirmed against real fleet data yet** — a genuinely open question carried forward honestly rather than presented as a finished, validated policy.

## `HardwareHealthReport.java` / `HardwareRecommendation.java`
Same append-only, one-run-per-row pattern as `Assessment`/`CheckResult`. Two details worth calling out specifically:
- **Nullable score columns, on purpose.** `cpuScore`, `batteryScore`, etc. are `Integer` (the boxed, nullable wrapper type), not primitive `int`. A desktop genuinely has no battery — scoring that as `0` would make it look like a *catastrophically dead* battery on every single desktop in the fleet, which is actively wrong, not just imprecise. `null` correctly means "not applicable," and every piece of code downstream (`HardwareHealthService.recordReport`, the frontend's `ScoreBar` component) is written to treat `null` and `0` as completely different things.
- **`succeeded` + `errorMessage`.** A hardware check that failed before it could collect anything still gets a permanent row — `succeeded = false`, every score field `null` (never `0`, for the same reason above), and `errorMessage` carrying why. This is the hardware-side twin of `AssessmentService.recordFailure`.

## `HardwareHealthRepository.java` / `HardwareRecommendationRepository.java`
Standard derived-query repositories, same shape as the posture package's.

## `scoring/` — `ComponentScorer.java`, `CpuScorer.java`, `MemoryScorer.java`, `StorageScorer.java`, `BatteryScorer.java`
**What this sub-package is, as a pattern:** `ComponentScorer<T>` is a generic **interface** — a contract with one method, `Integer score(T section)`, and no implementation. Four separate `@Component` classes each implement it for one specific piece of the raw report. **Why split into four tiny classes instead of one big scoring method:** each one is independently unit-testable, and each has genuinely different, self-contained math:
- `CpuScorer` — `100 - loadPercentage`, clamped to 0–100. Lower load = higher score. Documented explicitly as "reflects headroom, not hardware fault" — a CPU pegged at 100% isn't broken, it's just busy, and the score says so honestly.
- `MemoryScorer` — same shape, from `UsedPercent`.
- `StorageScorer` — the *proportion* of physical disks reporting `Healthy` (or the numeric code `0`, which is what PowerShell's `HealthStatus` actually returns for "healthy" — a real quirk this scorer specifically has to know about). One bad disk in a multi-disk machine drags the score down proportionally, not to zero.
- `BatteryScorer` — wear percentage = `FullChargedCapacity / DesignedCapacity`. Returns `null` (not `0`) whenever there's genuinely no usable battery data — the "not applicable" rule again, enforced at its actual source.

## `HardwareHealthService.java`
**What it is:** the write/read path for hardware evidence, and the actual home of the scoring-aggregation and banding logic.
- **`recordReport(...)`** — takes the four already-computed component scores, and computes `overallScore` as the average of *only the components that actually apply* (`componentCount` starts at 3 and only becomes 4 if `batteryScore != null`) — explicitly **not** treating a missing battery as a zero dragging the average down, the same principle threaded consistently through this whole package.
- **`bandFor(int score)`** — a simple cascading `if`/`return` ladder against the three threshold constants, turning a 0–100 number into one of the four `HardwareBand` values.
- **`recordFailure(...)`** — the failure-row writer described above, called by `JobWorker` when a `HARDWARE_CHECK` job fails outright.
- **`toResponse(...)`** — converts the entity (plus a separate repository lookup for its recommendations, since those live in their own table) into the `HardwareHealthResponse` DTO.

## `HardwareIngestService.java`
**What it is:** the layer that receives the *raw* JSON straight from `hardware_health_agent.ps1` (via `HardwareReportRequest`) and turns it into the clean, typed inputs `HardwareHealthService.recordReport` needs. This is genuinely the messiest layer in the backend, and deliberately so — it's where PowerShell's real-world quirks get absorbed *once*, so nothing downstream has to know about them:
- **The `ConvertTo-Json` single-element quirk.** PowerShell's JSON serializer collapses a one-item array into a bare object instead of a one-item array. A machine with exactly one disk or one battery therefore sends `{...}` instead of `[{...}]`. `asListOfMaps(Object v)` exists purely to normalize both shapes into a real `List<Map<String,Object>>` every time, so the scorers downstream never have to think about it (and never throw a `ClassCastException`, which is exactly what happened before this normalization existed).
- Validates that `endpoint.mac` is present, throwing `IllegalArgumentException` if not (turned into a clean `400` by `HardwareIngestExceptionHandler` below) — deliberately *not* using a Bean Validation annotation here, because the Javadoc explains a nested-object validation failure needs a clearer, more specific error message than the generic framework produces.

## `HardwareIngestController.java` / `HardwareQueryController.java`
Same ingest/query class-separation pattern as posture — write side only calls `HardwareIngestService`, read side only calls `HardwareHealthService`'s read methods.

## `HardwareIngestExceptionHandler.java`
**What it is:** a `@RestControllerAdvice(assignableTypes = { HardwareIngestController.class })` — this is Spring's mechanism for catching exceptions *centrally* rather than wrapping every controller method in its own try/catch. `assignableTypes` scopes it to only this one controller. `@ExceptionHandler(IllegalArgumentException.class)` catches specifically that exception type and `@ResponseStatus(HttpStatus.BAD_REQUEST)` turns it into a clean `400` with the exception's own message as the body — instead of an opaque, stack-trace-leaking `500`.

## `HardwareHealthNotFoundException.java`
Same `@ResponseStatus(HttpStatus.NOT_FOUND)` pattern, thrown when `getLatestForEndpoint` has nothing to return.

## `config/HardwareAgentProperties.java`
The hardware-agent twin of `PostureAgentProperties` — same `@ConfigurationProperties(prefix = "app.hardware")` binding pattern.

## `dto/HardwareReportRequest.java` / `dto/HardwareHealthResponse.java`
`HardwareReportRequest` is a `record` with a nested `record EndpointDto(...)` inside it — Java records can nest like this, and it keeps the "identity block" of the incoming JSON grouped exactly as PowerShell's `ConvertTo-Json` actually produces it. `HardwareHealthResponse` likewise nests a `record RecommendationDto(...)`.

---

# PART 8 — `ise/` — Cisco ISE Integration & Enforcement

**Why this package exists, and why it's kept so deliberately separate from everything else:** this is the *only* place in the entire backend allowed to make an outbound call that changes something on the real network (quarantine/restrict a device). Every other package only ever reads from or writes to Postgres. This isolation is the platform's whole reason for being trustworthy — "observation never becomes enforcement by accident" only holds if the code that *can* enforce is small, separate, and only ever triggered by an explicit human click.

## `IseTransport.java`
**What it is:** a Java `interface` — a contract with exactly two real operations (`publishPosture`, `publishEnforcement`) plus a health check (`reachable`), and zero implementation. **Why an interface here specifically:** it means the rest of the codebase (`IseActionService`) depends only on *what* ISE integration can do, never on *how* — so a second implementation (say, a future pxGrid-based transport) could be swapped in as a different Spring bean without touching a single line of the calling code. This is the classic "program to an interface, not an implementation" principle, applied because ISE integration is exactly the kind of thing likely to need a second implementation someday.

## `EnforcementAction.java`
A two-value enum: `RESTRICT`, `CLEAR`. The Javadoc is blunt about it: *"Never selected automatically — always the direct result of a `POST .../restrict` or `/clear` call."* The enum's very existence (rather than a raw boolean or string) makes "what enforcement actions exist" a closed, compiler-checked list.

## `IseResult.java`
A `record(boolean success, String detail)` — deliberately never `null` for `detail` (documented directly in the Javadoc), because callers store it verbatim in the permanent audit trail; a `null` detail there would be a useless audit row.

## `ErsIseTransport.java`
**What it is:** the one real implementation of `IseTransport`, talking to Cisco ISE's **ERS (External RESTful Services) API** — sometimes called the Context-In API.
- **Constructor logic:** if `!props.isConfigured()` (blank `base-url`), it deliberately builds `restClient = null` and every method checks that again before use — the app boots and runs fine even with zero ISE configuration; it just safely no-ops instead of crashing at startup.
- **`insecureHttpClient()`** — builds a Java `HttpClient` with a custom `TrustManager` that accepts *any* TLS certificate, no validation at all. This is only wired in when `app.ise.verify-tls: false` — an explicit, documented, deliberate choice for a lab/self-signed ISE instance, mirroring what the Python prototype did with `urllib3.disable_warnings(...)`. **This is a real security trade-off, not a default** — it's off (`verify-tls: true`) in any real production hardening checklist for this project.
- **`endpointIdFor(String mac)`** — ISE's ERS API requires you to look up its own internal opaque ID for an endpoint (by MAC) before you can `PUT` an update to it; this method does that lookup, defensively unwrapping several layers of nested `Map`/`List` from the raw JSON response (`SearchResult.resources[0].id`) without ever assuming the shape is exactly as expected.
- **`publishPosture(...)`** — writes the endpoint's latest status as ISE **custom attributes** (`ExternalComplianceStatus`, `PostureLastChecked`, `PostureFailedChecks`). Deliberately does *not* decide network access itself — it hands ISE a fact, and ISE's own Authorization Policy (configured separately, inside ISE, outside this codebase entirely) is what decides what to do with that fact.
- **`publishEnforcement(...)`** — branches on `props.getEnforcementMode()`:
  - **`ANC` mode** — calls ISE's Adaptive Network Control endpoints directly (`/ers/config/ancendpoint/apply` or `/clear`) with a named policy.
  - **`ATTRIBUTE` mode** — writes a compliance attribute, then triggers an immediate **CoA (Change of Authorization) re-authentication** by looking up the device's live session (`sessionDetailFor`) to find which ISE Policy Server (PSN) is handling it, then calling ISE's `CoA/Reauth` endpoint against that specific PSN. This forces ISE to re-evaluate the session against its policy *right now*, using the attribute just written, rather than waiting for the device's next natural re-auth.
  - Note the deliberate asymmetry: under attribute mode, `CLEAR` doesn't try to "remove" an ANC policy that was never applied that way — it explicitly returns a message telling the operator to re-share posture instead, since that's the actual mechanism that changes ISE's re-evaluation under this mode. This is a real, documented design decision, not a missing feature.
- **`sessionDetailFor(String mac)`** — parses ISE's XML session-detail response generically: it walks *every* XML element (`getElementsByTagName("*")`) and, for any leaf element with actual text content, stores it in a flat `Map<String,String>` keyed by tag name. This avoids hardcoding a brittle, specific XML shape that might shift between ISE versions — it just captures whatever's there.

## `IseActionService.java`
**What it is:** the actual business-logic layer sitting between `IseActionController` and `IseTransport` — and the place where the audit-write guarantee is enforced in code, not just documentation.
- **`sharePosture(...)`** — looks up the endpoint's latest assessment; if there isn't one yet, it doesn't throw an unhandled error, it builds a graceful `IseResult(false, "No stored assessment...")` instead, so the caller (and the audit trail) get a clear, specific reason rather than a stack trace.
- **`restrict(...)` / `clearRestriction(...)`** — both funnel through one shared private `enforce(...)` method, differing only in the `EnforcementAction` value and the audit `actionType` string passed in — avoiding duplicating the "call transport, then write audit" logic twice.
- **`writeAudit(...)`** — called at the end of **every single path above, success or failure alike**. This is the concrete mechanism behind "every ISE action, success or failure, writes exactly one audit row, unconditionally" — it isn't a policy statement, it's the fact that there is no return statement anywhere in this class that skips this call.
- **`summarizeFailedChecks(...)`** — builds the "which checks failed" string sent to ISE as the `PostureFailedChecks` attribute, from the same non-compliant-checks-only filtering logic as `PostureIngestService.summarize`.

## `IseActionController.java`
Three routes, one method each, each one line of actual logic: call the service, map success/failure to an HTTP status (`200` on success, `502 Bad Gateway` on failure — `502` specifically because it correctly signals "we're fine, but the upstream system (ISE) we depend on failed," distinct from a `500` which would imply this backend itself is broken). Deliberately its **own controller class**, not merged with `PostureIngestController` — the same structural-separation principle as Part 6's ingest/query split, applied at the highest-stakes boundary in the whole system: "record evidence" and "act against the network" can never share a call stack.

## `config/IseProperties.java`
`@ConfigurationProperties(prefix = "app.ise")`, same binding pattern as the agent properties classes, but with a nested `public enum Mode { ATTRIBUTE, ANC }` right inside it — scoping that enum to exactly the one setting it configures. `isConfigured()` is a small convenience method (`baseUrl != null && !baseUrl.isBlank()`) that every caller checks before attempting to talk to ISE at all, rather than each caller re-deriving "is ISE set up" from raw field checks.

## `dto/ShareRequest.java` / `dto/EnforcementRequest.java`
Tiny validated records (`@NotNull endpointId`), the request bodies for the three action routes.

---

# PART 9 — `session/` — ISE Connect/Disconnect Polling

**Why this exists as its own package, separate from `endpoint/`:** it's a genuinely distinct *responsibility* — "keep polling an external system and reconcile state" — even though it writes onto the `endpoint` table. Keeping it separate means the polling/scheduling concern doesn't bloat `EndpointService`, which stays focused purely on CRUD-and-upsert logic.

## `SessionEventType.java`
A tiny enum: `CONNECTED`, `DISCONNECTED` — the two kinds of event the session log records.

## `EndpointSessionLog.java` / `EndpointSessionLogRepository.java`
An append-only event-log entity — every connect and every disconnect is its own permanent row (`endpointId`, `eventType`, `ipAddress`, `eventAt`), never overwritten. This is what makes "how long was this device offline, and when did it reconnect" an answerable historical question instead of a fact that only the *current* state can express.

## `IseSessionClient.java`
**What it is:** the raw HTTP client that actually talks to ISE's **MNT (Monitoring) ActiveList API** — a different ISE API surface than the ERS one `ErsIseTransport` uses (MNT is for querying live session state; ERS is for configuration/enforcement).
- **`fetchActiveSessions()`** — the method's entire contract, stated directly in its Javadoc, is: **it never throws.** Every failure mode — ISE unreachable, wrong credentials, malformed XML, ISE not configured at all — is caught internally and turned into an empty `List.of()`. **Why this matters so much for a method that's called from inside `@Scheduled`:** an uncaught exception thrown out of a scheduled method silently kills *all future invocations* of that schedule — Spring doesn't restart it. So "never throw, degrade to empty" isn't a style preference here, it's what keeps the entire 15-second polling loop alive forever, tick after tick, no matter what ISE does on the other end.
- **`parseActiveList(String xml)`** — parses ISE's session-list XML using Java's built-in `DocumentBuilderFactory`/DOM parser (no extra XML library dependency needed), pulling `calling_station_id` (the MAC) and `framed_ip_address` (the IP) out of each `<activeSession>` element.

## `IseSessionWatcher.java`
**What it is:** the `@Scheduled` loop itself — `tick()` runs on `app.ise.session-poll-interval-ms` (15 seconds by default).
- Fetches the active list; if empty, returns immediately (either genuinely no sessions, or ISE was unreachable — already logged by the client, nothing more to do this tick).
- For every currently-active session: checks whether the endpoint *was* already marked connected (`wasConnected`), then calls `EndpointService.markConnected(...)` regardless. If it **wasn't** already connected — i.e. this is a fresh reconnect — it calls `jobService.enqueueIfDue(ep.getId(), JobType.POSTURE_CHECK)`. This is the automatic-recheck-on-reconnect behavior: a device coming back online gets a fresh posture check queued for it without any human having to notice and trigger one manually.
- For every endpoint currently flagged `connected = true` in the database that *isn't* in this tick's active list: marks it disconnected. This is the direct fix for a real bug class from the Python prototype ("connection status never expires") — a device that drops off ISE's session list is detected and flipped within one poll interval, rather than staying "connected" forever in stale data.
- **`normalizeMacForCompare(...)`** — a private, local copy of the same MAC-normalization logic as `EndpointService.normalizeMac`. The comment directly explains why it's duplicated here rather than shared: ISE's own MAC formatting can vary, so every lookup against the database (which stores the canonical normalized form) needs this same normalization applied first, right at the comparison site.

---

# PART 10 — `audit/` — The Permanent ISE-Action Log

**Why this is its own package, not folded into `ise/`:** the audit trail is conceptually "read-only history that anyone with a token can inspect," while `ise/` is "the machinery that changes things." Splitting them means the read-side API (`AuditQueryController`) has a completely separate, simple surface from the action-triggering side — you can hand someone read access to the audit log without it implying anything about their ability to *trigger* actions (a real, useful distinction for future RBAC work).

## `IseActionAudit.java`
**What it is:** the entity for `ise_action_audit` — one row per Share/Restrict/Clear attempt, success or failure, written *only* by `IseActionService.writeAudit(...)` and nowhere else in the entire codebase. Fields: `endpointId`, `actionType` (a plain `String`, not an enum here — deliberately open-ended text matching whatever `IseActionService` passes in, rather than a second enum duplicating `EnforcementAction`), `operator` (the authenticated JWT subject — who actually clicked the button), `succeeded`, `detail`, `occurredAt`.

## `IseActionAuditRepository.java`
Two derived-query methods: `findAllByEndpointIdOrderByOccurredAtDesc` (one device's history) and `findAllByOrderByOccurredAtDesc` (the whole trail). The Javadoc on the interface itself states plainly: *"Reads only ever list rows — the trail is append-only."* There is no update or delete method defined anywhere for this entity, by design — you cannot edit history out of an audit log through this codebase's own API surface.

## `AuditQueryController.java`
One route, `GET /api/v1/audit/ise-actions`, with an optional `endpointId` query parameter that switches between the two repository methods above. This is the entire read API for "show me everything that's ever been done against ISE from this platform" — deliberately simple, because the value here is completeness and permanence, not clever querying.

---

# PART 11 — `security/` — Authentication & Authorization

**Why this needs its own package, thoroughly:** the original Python prototype's dashboard had *no authentication at all* — a real, explicitly flagged gap. This package exists to close it properly, and to give the two very different kinds of caller (a human at a browser, a PowerShell script running as a child process) two appropriately different ways to prove who they are.

## `Role.java`
An enum with, deliberately, exactly one value: `ADMIN`. The Javadoc is explicit about *why* more roles (Security Analyst, Network Operator, Viewer — all named in the design docs) aren't here yet: they get added "only when there is a second role that is actually enforced differently somewhere in the app, not speculatively." This is a real engineering discipline worth internalizing — building out a 4-role permission matrix before a second role's *actual different behavior* exists is pure guesswork dressed up as architecture.

## `User.java`
The `@Entity` for `app_user`. `passwordHash` is explicitly named (not `password`) and the Javadoc states plainly: *"Passwords are stored only as BCrypt hashes, never in plain text."* **What BCrypt actually is:** a one-way hashing algorithm purpose-built for passwords — given the same password, it produces a different-looking hash every time (because it generates a random salt internally and embeds it in the output), and it's deliberately *slow* (tunable "cost factor"), which is a feature, not a bug: it makes brute-forcing stolen hashes computationally expensive.

## `UserRepository.java`
One method: `findByUsername(String username)` — returns an `Optional<User>` (Java's explicit "this might not exist" wrapper type, forcing calling code to handle the empty case rather than risking a `NullPointerException`).

## `JwtService.java`
**What it is:** the class that creates and verifies the actual JWT strings.
- **Constructor:** `Keys.hmacShaKeyFor(secret.getBytes(...))` turns the configured secret string into a cryptographic key object. The Javadoc notes JJWT *rejects* any secret shorter than 256 bits at startup (`WeakKeyException`) — a fail-fast guard against an accidentally-weak production secret, rather than silently issuing insecure tokens.
- **`generateToken(String username, String role)`** — builds a token with the username as its `subject`, the role as a custom `claim`, an issue time, and an expiry (`app.jwt.expiration-minutes`, 480 = 8 hours by default), signed with `signWith(key)`.
- **What a JWT actually *is*, concretely:** three base64-encoded segments separated by dots — a header, a payload (the claims above, in plain readable JSON once decoded — a JWT is **not encrypted**, only *signed*; anyone can read its contents, they just can't forge or alter them without knowing the secret), and a signature. `isValid(token)` just attempts to parse-and-verify the signature and checks expiry; any failure (bad signature, expired, malformed) is caught and returns `false`.

## `JwtAuthFilter.java`
**What it is:** a `OncePerRequestFilter` — a Spring Security filter guaranteed to run exactly once per incoming request, early in the pipeline, before it reaches any controller.
- Reads the `Authorization` header; if it starts with `"Bearer "`, strips that prefix and hands the rest to `JwtService.isValid(...)`.
- On success, builds a `UsernamePasswordAuthenticationToken` (Spring Security's internal representation of "this request is authenticated as this principal, with these authorities") and stashes it in `SecurityContextHolder` — a thread-local holder that the rest of the request's processing (including `Authentication auth` parameters in controller methods like `IseActionController.share(...)`) can read from.
- **Critically, it does nothing on failure** — no token, or an invalid one, and the filter just calls `filterChain.doFilter(...)` and moves on, *unauthenticated*. It's `SecurityConfig`'s authorization rules (below) that actually reject the request later — this filter's only job is "figure out who's asking, if anyone."

## `PostureApiKeyFilter.java`
**What it is:** the second, narrower authentication path, exclusively for the two PowerShell agent ingestion routes.
- **`shouldNotFilter(request)`** — this is the "should this filter even bother running" hook; it returns `true` (skip) for every request *except* a `POST` to exactly `/api/v1/posture` or `/api/v1/hardware-health` — so this filter has literally zero effect on any other route in the app.
- Reads the `X-Posture-Api-Key` header. If it's simply absent, it steps aside (a normal admin JWT can still authenticate that same route, useful for testing from Swagger). If it's present, it's checked with `MessageDigest.isEqual(...)` — **constant-time comparison**, explained in Part 8 of the earlier reply: this prevents a timing-attack side channel where a naive string comparison would return fractionally faster the sooner it hits a wrong character, theoretically letting an attacker guess the secret one byte at a time by measuring response latency.
- A wrong key gets a hand-written `401` response, written directly via `response.getWriter()` rather than `response.sendError(...)` — the code comment explains why: `sendError` triggers Spring's `/error` dispatch, which Spring Security would then evaluate *again* and could turn into a confusing `403` instead of the intended `401`.
- A correct key grants an authority of exactly `ROLE_AGENT` — nothing more. This is the concrete mechanism behind "the API key can submit a report, and can do nothing else": every other route in the app requires either `ROLE_ADMIN` or simply doesn't recognize `ROLE_AGENT` as sufficient.

## `SecurityConfig.java`
**What it is:** the single place that assembles every rule above into one enforced policy.
- `@Bean public SecurityFilterChain filterChain(HttpSecurity http)` — this method *is* the security policy, expressed as a fluent builder chain:
  - `.csrf(csrf -> csrf.disable())` — CSRF (Cross-Site Request Forgery) protection exists to defend cookie-based sessions; this API is stateless and token-based (no cookies, no forms), so that specific attack surface doesn't apply here, and the protection is explicitly disabled rather than left as pointless friction.
  - `.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))` — tells Spring Security never to create or rely on an `HttpSession` at all; every request must carry its own proof of identity (the JWT), every time.
  - `.authorizeHttpRequests(auth -> auth ... )` — the actual allow/deny table: `/api/v1/auth/**`, health, and Swagger UI routes are `permitAll()` (must be reachable *before* anyone has a token, or the whole system is unbootstrappable); the two ingestion POST routes require `hasAnyRole("AGENT", "ADMIN")`; `.anyRequest().authenticated()` is the catch-all default-deny — literally everything else in the entire application requires *some* valid authentication, with no route accidentally left open by omission.
  - `.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)` and the same for the API key filter — this is where the two custom filters from above are actually spliced into Spring Security's internal filter chain, positioned to run before Spring's own default username/password filter (which this app doesn't otherwise use, since login is handled by `AuthController` directly).
- `@Bean public PasswordEncoder passwordEncoder()` — returns a `BCryptPasswordEncoder`, wired via Spring's DI into both `AuthController` (to verify a login) and `seedAdmin` (to hash the initial password).
- `@Bean public CommandLineRunner seedAdmin(...)` — a `CommandLineRunner` is a bean whose `run(...)` method Spring Boot calls automatically, once, right after the application context finishes starting up. This one checks `if (userRepository.count() == 0)` — only on a genuinely empty database — and creates one admin user from `app.seed-admin.username`/`password` in `application.yml`. This is deliberately the *entire* extent of "user management" in this version: enough that a real login is required everywhere, without building a user-management UI/API for a system with exactly one operator.

## `AuthController.java`
**What it is:** the only route in the whole app that issues tokens — `POST /api/v1/auth/login`.
- `@SecurityRequirement(name = "")` on the method — an OpenAPI/Swagger-specific annotation that overrides the *global* security requirement (declared once in `OpenApiConfig`) just for this one route, so Swagger UI correctly shows it as callable *without* first clicking "Authorize."
- The actual check: `userOpt.isEmpty() || !userOpt.get().isEnabled() || !passwordEncoder.matches(...)` — all three failure conditions (unknown username, disabled account, wrong password) are deliberately collapsed into **one identical response** (`401 "Invalid username or password"`). **Why deliberately vague instead of specific:** a response that says "no such user" versus "wrong password" would let an attacker enumerate which usernames are valid, one guess at a time — collapsing them closes that information leak.

---

# PART 12 — `config/` — Cross-Cutting Configuration

## `OpenApiConfig.java`
**What it is:** a small, pure-annotation class (`@OpenAPIDefinition`, `@SecurityScheme`) with no methods at all — its entire job is metadata.
- `@OpenAPIDefinition(info = @Info(title = "...", version = "...", description = "..."), security = @SecurityRequirement(name = "bearerAuth"))` — sets the title/description shown at the top of the Swagger UI page, and declares that **every** route requires the `bearerAuth` scheme *by default* (which is exactly the global rule `AuthController.login` above has to explicitly override).
- `@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")` — this is what actually makes the "Authorize" button with a token-paste box appear in Swagger UI at all; without this annotation existing somewhere in the app, Swagger would have no idea the API uses bearer tokens and couldn't offer to attach one to your test calls.

---

# PART 13 — `resources/` — Runtime Configuration & Schema

## `application.yml`
**What it is:** the single file holding every piece of runtime configuration for the whole application, in YAML (a human-readable, indentation-based structured-data format — the same underlying idea as JSON, easier for humans to hand-edit).

Section by section:
- `spring.datasource.url/username/password` — how to reach Postgres. Note the `${DB_USERNAME:ep_app}` syntax — this is Spring's **environment-variable-with-default** placeholder: use the `DB_USERNAME` environment variable if it's set, otherwise fall back to the literal `ep_app`. This is exactly how a real secret gets swapped in for production (set the env var) without ever touching this checked-in file.
- `spring.jpa.hibernate.ddl-auto: validate` — this single line is a deliberate safety switch: Hibernate is told to *validate* that the Java entities match the actual database schema at startup (and fail loudly if they don't), but never to auto-generate or alter tables itself. Flyway alone owns schema changes.
- `spring.flyway.enabled: true` / `locations: classpath:db/migration` / `baseline-on-migrate: true` — turns Flyway on, points it at the migration files bundled inside the JAR itself, and tells it to treat an already-existing-but-unversioned database as a valid starting baseline rather than refusing to run.
- `server.port: 8090` — the embedded Tomcat's port.
- `app.jwt.secret` / `app.jwt.expiration-minutes` — feeds `JwtService`'s constructor.
- `app.seed-admin.username` / `password` — feeds `SecurityConfig.seedAdmin`.
- `app.jobs.poll-interval-ms: 3000` — feeds `JobWorker`'s `@Scheduled` annotation.
- `app.ise.*` — feeds `IseProperties`.
- `app.posture.*` / `app.hardware.*` — feed `PostureAgentProperties`/`HardwareAgentProperties`.

**The general principle this whole file embodies:** nothing environment-specific is hardcoded in Java source. Every value that might differ between your laptop, a teammate's laptop, and a real deployment lives here, in one place, with sane local defaults and an explicit override mechanism.

## `db/migration/` — The Schema, Table by Table

Each file is a **Flyway migration** — applied exactly once, in the numeric order of its filename, tracked in Flyway's own internal bookkeeping table so it never re-runs. Read together, in order, they *are* the database's complete history.

### `V1__create_users_and_roles.sql` — table `app_user`
| Column | Type | Purpose |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | internal identifier |
| `username` | `TEXT UNIQUE` | login name |
| `password_hash` | `TEXT` | BCrypt hash, never plaintext |
| `role` | `TEXT DEFAULT 'ADMIN'` | stored as text, not an FK — see `Role.java`'s note on why there's only one role so far |
| `enabled` | `BOOLEAN DEFAULT TRUE` | a disabled user cannot log in, without deleting their row/history |
| `created_at` | `TIMESTAMPTZ` | |

The file's own comment is candid about the design trade-off: role as a plain column, not a `user_roles` join table, "for now" — a join table is "the obvious next step once there's more than one role in real use — not adding it speculatively before that's true." Same YAGNI discipline as `Role.java`.

### `V2__create_endpoints.sql` — table `endpoint`
Holds every device the platform has ever seen: `mac_address` (`UNIQUE`, the real business key), `ip_address`, `hostname`, `os_name`/`os_version`, `connected` + `session_started_at`/`last_disconnected_at` (the independent connection-state fields), `manufacturer`/`model`/`serial_number` (hardware identity), `first_seen_at`/`last_seen_at`, `created_at`/`updated_at`. Two indexes: on `connected` (the session watcher's disconnect-detection query filters on this) and on `last_seen_at` (for any "recently seen" query).

### `V3__create_posture_jobs.sql` — table `posture_job`
The queue table itself: `endpoint_id` (a real `REFERENCES endpoint(id) ON DELETE CASCADE` foreign key — delete an endpoint, its jobs go with it automatically, no orphaned rows possible), `job_type`, `status`, `priority`, `attempt_count`/`max_attempts`, `error_message`, and the full timestamp set including `next_attempt_at` (`NULL` = eligible immediately, otherwise the backoff deadline). The file's comment states directly: this table **replaces** `pending_devices.txt`/`seen_macs.txt` from the Python prototype. The index `idx_posture_job_claimable ON posture_job(status, next_attempt_at)` exists specifically because those are the exact two columns `findNextClaimable()`'s `WHERE` clause filters on — without it, that query would have to scan the entire table on every single 3-second tick as the table grows.

### `V4__create_assessments.sql` — tables `assessment` and `check_result`
`assessment`: `endpoint_id` (FK, cascade delete), `job_id` (FK to `posture_job`, but `ON DELETE SET NULL` — a job can be purged later without invalidating the historical assessment it produced), `status`, `detail`, `started_at`/`completed_at`/`created_at`.
`check_result`: `assessment_id` (FK, cascade delete), `check_type` (plain text — `FIREWALL`, `OPEN_PORTS`, `APPLICATIONS`, and open-ended for future check types with zero migration needed), `status`, `details JSONB`.
Two indexes on `check_result.details` are notable: a plain index doesn't exist for JSONB content — instead there's `USING GIN (details)`, a **GIN (Generalized Inverted Index)**, purpose-built for indexing *into* the structure of a JSON document rather than treating the whole column as one opaque blob, letting a future query like "find every check where `blockedPorts` contains 3389" run efficiently instead of scanning every row.

### `V8__create_hardware_health.sql` — tables `hardware_health` and `hardware_recommendation`
`hardware_health`: `endpoint_id` (FK), `job_id` (FK, `ON DELETE SET NULL`), hardware identity fields, the four score columns (`cpu_score`/`memory_score`/`storage_score` all `NOT NULL CHECK (... BETWEEN 0 AND 100)` — a `CHECK` constraint is the database itself refusing to store an impossible value like `150` or `-5`, enforced no matter what bug might exist in the Java code that tries to insert it; `battery_score` is the one nullable exception, deliberately, as explained throughout Part 7), `overall_score`/`overall_band` (also `CHECK`-constrained to only the four legal band names), event/warranty fields, `raw_report JSONB NOT NULL`, `collected_at`.
`hardware_recommendation`: `hardware_health_id` (FK, cascade delete), `priority` (`CHECK (priority IN ('LOW','MEDIUM','HIGH'))`), `area`, `action`.

### `V9__add_hardware_health_failure_tracking.sql` — an *alteration*, not a new table
This migration runs `ALTER TABLE` five times to **drop** the `NOT NULL` constraint on the four score columns (originally required, but a failed collection run genuinely has no scores to report), and **adds** two new columns: `succeeded BOOLEAN NOT NULL DEFAULT TRUE` and `error_message TEXT`. Its own comment states the reasoning plainly: without this, "the fact of the failure lives only in `posture_job.error_message` (overwritten on every retry, gone once the job succeeds or is purged)" — this migration is what makes a failed hardware check leave *permanent* evidence, mirroring the exact same principle already applied to `assessment`/`check_result` on the posture side. This is a good real-world example of what a migration *for* — the schema evolving to fix a genuine gap discovered after `V8` was already live, without anyone hand-editing a production database.

### `V10__create_endpoint_session_log.sql` — table `endpoint_session_log`
`endpoint_id` (FK, cascade delete), `event_type` (`CHECK (event_type IN ('CONNECTED','DISCONNECTED'))`), `ip_address`, `event_at`. One composite index, `(endpoint_id, event_at DESC)` — exactly the shape needed for "this device's connection history, newest first," the only query pattern this table actually serves.

### `V11__create_ise_action_audit.sql` — table `ise_action_audit`
`endpoint_id` (FK, cascade delete), `action_type` (`CHECK` constrained to the three legal action names), `operator` (nullable *only* for any pre-auth rows that might theoretically exist — in practice, always populated once login is required), `succeeded`, `detail`, `occurred_at`. Two indexes: one composite `(endpoint_id, occurred_at DESC)` for per-device history, one plain `(occurred_at DESC)` for the whole-fleet audit view.

### The general theme across every migration file
Look at the `ON DELETE` behavior chosen for every foreign key, and it's never arbitrary:
- `ON DELETE CASCADE` — used wherever the child row has *no meaning at all* without its parent (a `check_result` without its `assessment`, a `posture_job` without its `endpoint`). Delete the parent, the children vanish automatically — the database enforces "no orphans" for you.
- `ON DELETE SET NULL` — used specifically for `job_id` foreign keys on `assessment` and `hardware_health`. The reasoning: the *evidence* (the assessment itself) must survive even if the *job that triggered it* is later purged for housekeeping — losing the link to a purged job is acceptable, losing the evidence itself is not.

That distinction — thought through per-relationship rather than copy-pasted — is exactly the kind of structural discipline the whole Java rewrite exists to enforce, versus the Python prototype's plain MAC-string relationships with no database-level enforcement at all.

---

# PART 14 — How A Request Actually Flows, End To End

To tie every package above together, here's the literal path of one real request — a PowerShell agent submitting a posture report — through every layer:

1. `JobWorker.runPostureAgent(...)` starts `powershell.exe posture_agent.ps1` as a child process, with `POSTURE_API_KEY` in its environment.
2. The script collects data locally, then does its own outbound `Invoke-RestMethod POST http://localhost:8090/api/v1/posture`, with `X-Posture-Api-Key` set in its headers.
3. That HTTP request hits Spring's filter chain first. `PostureApiKeyFilter.shouldNotFilter` sees this *is* one of its two watched routes, so it runs; it validates the key with `MessageDigest.isEqual`, and grants `ROLE_AGENT`.
4. `SecurityConfig`'s authorization rule for this exact route (`hasAnyRole("AGENT", "ADMIN")`) is satisfied — the request is allowed through.
5. Spring's routing dispatches it to `PostureIngestController.ingest(...)`. `@Valid` has already rejected the request with a `400` if `macAddress` or `status` were missing, *before* this method body even runs.
6. The controller calls `PostureIngestService.ingest(req)`.
7. That calls `EndpointService.upsertByMac(...)` — normalizes the MAC, finds-or-creates the `Endpoint` row, updates `lastSeenAt`.
8. It computes the true overall status (worst-of-all-checks), then calls `AssessmentService.recordAssessment(...)` — which saves one `Assessment` row and one `CheckResult` row per check, all inside a single `@Transactional` boundary. If anything fails partway, none of it commits.
9. Control returns up the stack; `PostureIngestController` wraps the resulting `AssessmentResponse` in a `201 Created` HTTP response.
10. Back in PowerShell, the script sees the successful HTTP response, and separately prints its `RESULT_JSON:` line with `submitted: true`.
11. `JobWorker`, which has been draining that stdout on its own thread the entire time, sees the process exit, parses that final `RESULT_JSON` line, sees `submitted: true`, and calls `jobService.markComplete(job.getId())` — the very last step, closing the loop the whole `job/` package exists to manage.

Every package in this document is a link in that one chain — nothing here is incidental.
