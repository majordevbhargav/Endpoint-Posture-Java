# PR6.5 / PR6.6 implementation log

Updated: 2026-11-11

## Repository state

- Starting checkout verified: `feature/pr6-5-reliable-result-delivery` at `71beb5ddd8f782dab3306eee01c4f08a82f43fef`; initial tree clean.
- Integration base `origin/feature/aws-linux-backend-worker-separation` was present at the same starting commit. No changes have been made to that branch.
- GitHub search found no open PR for `feature/pr6-5-reliable-result-delivery`.
- SQLite outbox uniqueness follows the backend's global `(job_id, attempt)` receipt uniqueness. Matching retries require the same worker identity and hash; conflicting records are retained/rejected.

## PR6.5 implementation progress

Implemented in the worker module:

- Pinned Xerial SQLite JDBC dependency; versioned schema; WAL; FULL synchronous commits; per-connection busy timeout and foreign-key enforcement.
- Explicit transaction/connection handling, immutable payload/identity triggers, one-way delivery-state transitions, and a delete guard for unresolved rows.
- Durable result handoff that saves worker ID, shard, claim/fencing fields, full completed snapshot, exact result request bytes, SHA-256 integrity value, retry state, lease budget and acknowledgement.
- Single background dispatcher; exact-byte authenticated delivery; full acknowledgement validation; bounded jittered retries and Retry-After; permanent failure retention; bounded lease-renewal budget and absolute grace deadline.
- Production `WorkerMain` wiring, persistent data-directory configuration, Windows ACL provisioning script, operations documentation, statistics and bounded shutdown.
- Added SQLite persistence/restart/integrity/concurrency/immutability tests and dispatcher retry/ack/lease tests.

Verification completed so far:

- `worker-runtime`: final `mvn -s maven-local-settings.xml -B '-Duser.timezone=UTC' clean verify` passed: 103 tests, 0 failures/errors, 1 expected skip. Two Windows DPAPI tests, real PowerShell tests and SQLite persistence tests ran on Windows 11 / Java 21.
- `backend`: `mvn -s maven-local-settings.xml -B '-Duser.timezone=UTC' verify` passed with Docker/Testcontainers available: 549 tests, 0 failures/errors/skips. The backend contract was not changed.
- Shaded JAR inspection found `org/sqlite/native/Windows/x86_64/sqlitejdbc.dll`; a smoke program loaded SQLite from the packaged JAR, opened a database, set WAL, and created a table (`sqlite-native-loaded`). The temporary smoke files were removed.
- `git diff --check` passed on the current implementation review; rerun before commit.
- Maven's configured user settings point to unwritable `C:\.m2`; temporary workspace-local Maven settings were used and removed. The Maven cache remains ignored under `worker-runtime/maven-cache/`.
- Docker Desktop was accessed for Testcontainers after explicit command approval; Testcontainers cleaned up its PostgreSQL containers.

Remaining PR6.5 gate work:

- Rerun focused dispatcher tests after the last diagnostic-message-only change.
- Inspect complete tracked and untracked diff, run `git diff --check`, secret scan, and verify generated Maven cache/settings are excluded/removed.
- ACL script is parameterized but has not been applied: the repository does not define the worker service account, so no identity was assumed. Review service-account setup evidence and state this limit in the PR.
- Commit, push PR6.5 branch, create PR against `feature/aws-linux-backend-worker-separation`.

## PR6.6 progress

Not started. Start only after PR6.5 verification, commit, push and PR creation are complete. Create `feature/pr6-6-worker-reliability-hardening` from the PR6.5 commit, then implement end-to-end crash/recovery, fencing/concurrency and outage hardening. Do not modify the integration branch or merge either PR.

## Known verification limits / outstanding issues

- No actual worker service identity is provisioned in this checkout. ACL behavior under a designated service identity and service restart under WinSW require that deployment identity to be selected/configured.
- Backend Testcontainers tests exercise the existing result endpoint/service; current worker dispatcher tests use a local HTTP test server. A true worker-to-live-backend lifecycle test remains PR6.6 work.
- No frontend files or contracts have changed; frontend checks are not applicable so far.
