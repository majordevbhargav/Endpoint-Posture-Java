# PR6 Operational Verification

Date: 2026-11-11

## A. Environment and repository state

- Repository: `Endpoint-Posture-Java` (local filesystem path omitted).
- Expected base state was verified before work: `feature/aws-linux-backend-worker-separation` at `8f58b941925dc52687f14af10112a3b896e2b426`, with a clean tracked tree. Work is isolated on `feature/pr6-operational-readiness`; the operational checks were captured before subsequent documentation/deployment review commits.
- OS: Windows 11, build `10.0.26340`.
- Java: Eclipse Temurin `21.0.12.1`; Maven `3.9.16`; Docker Desktop `28.4.0`.
- Backend test environment: disposable PostgreSQL 16 Testcontainers; backend verification completed successfully. No production infrastructure or credentials used.
- Current interactive command identity: a non-elevated sandbox account (machine and account names omitted). It was not used as a substitute for service testing. `Get-LocalUser svc-posture-worker` found no account; `Get-Command winsw.exe` found no binary on PATH; the default ProgramData data directory/state file are absent. `sc.exe query EndpointPostureWorker` and `sc.exe qc EndpointPostureWorker` both returned error 1060 (service not installed). A `Get-CimInstance Win32_Service` query was denied by the restricted account.
- WinSW v2.12.0 is the documented target based on official WinSW v2 command/XML documentation and existing repository v2 usage. No WinSW binary was available locally, so its file metadata/hash and service commands could not be exercised here.
- No worker service is installed. Worker data directory `%ProgramData%\EndpointPostureWorker` was absent before work. No ACL was applied.

### Deployment design prepared

A worker-only deployment package now defines service `EndpointPostureWorker`, with a dedicated local least-privilege identity `COMPUTERNAME\svc-posture-worker`. This is the selected deployment design; it is **not provisioned or observed as an actual service identity**. It is distinct from backend `svc-posture`. No service identity can honestly be reported as running in this environment.

The deployment package stages the shaded worker JAR, scripts and organization-approved WinSW v2.12.0 binary; provides separate account provisioning, ACL, credential initialization, install, start, stop and removal procedures; and documents explicit paths:

- Installation: `%ProgramFiles%\EndpointPostureWorker` (service account read/execute).
- Protected worker state: `%ProgramData%\EndpointPostureWorker\state.json`.
- SQLite outbox and WAL/SHM files: `%ProgramData%\EndpointPostureWorker`.
- Worker and bootstrap logs: `%ProgramData%\EndpointPostureWorker\logs`.
- Data/log access: service account modify; install access: read/execute. SYSTEM and local Administrators retain administrative access; unrelated inherited access is removed by the explicit ACL procedure.

The service starts in Manual mode in its template; the installer configures and checks the named account before setting Automatic startup and leaves the service stopped. PowerShell execution is disabled in the template. The 45-second stop timeout and bounded WinSW restart sequence are documented. DPAPI remains CurrentUser: credential enrollment must run with the service account’s loaded profile and the same account must run the service. No production Java/runtime security code was changed.

No account, password, security policy, ACL, service, production configuration, or existing user service was created or modified. Administrative scripts are supplied for an authorized operator to execute after reviewing the host-specific paths and policy.

## B. Verification matrix

| Check | Status | Execution and evidence | Limitation |
|---|---|---|---|
| Worker WinSW install | **BLOCKED** | Static inspection found no pre-existing worker service. New worker-specific template and scripts are present under `deploy/`; PowerShell parser reported 0 syntax errors across 8 scripts; XML parsed successfully. | Approved WinSW binary and dedicated account are not provisioned. No install command was run. |
| Service identity | **BLOCKED** | Deployment design names local `COMPUTERNAME\svc-posture-worker`; installer verifies SCM identity before enabling automatic startup. | No account/service exists to query, so this is not actual identity evidence. Administrator must provision account, grant Log on as a service, and verify `Win32_Service.StartName`. |
| Service startup | **BLOCKED** | Start script checks service identity, protected state, SCM Running state and worker process. | Script was only parsed/reviewed. No WinSW binary/account/service. |
| Service shutdown and genuine restart | **BLOCKED** | Stop script uses SCM and checks service stopped/process exit; no process kill is used. | Not executed. This environment lacks an installed worker service. |
| DPAPI credential persistence | **PASS** (interactive identity round trip); **BLOCKED** (intended service identity and SCM restart) | Current worker run: `DpapiSecretProtectorTest.roundTripsThroughRealDpapi` passed under the interactive sandbox account. Surefire XML shows 3 tests, 0 failures/errors, 1 skipped; the skipped test was `otherPlatformsFailClearly`, disabled on Windows. An earlier restricted run errored because that process had no loaded profile. | No DPAPI credential was provisioned for `svc-posture-worker`; no service process restart was performed. The interactive identity is not a substitute for the intended service identity. |
| DPAPI identity compatibility | **BLOCKED** | Enrollment script checks the exact local account identity and uses secure input; documentation requires a loaded profile. | No worker account/profile; no cross-account test performed. |
| SQLite persistence | **PASS** (automated disposable DB) | Worker `clean verify` includes `SqliteJobResultHandoffTest`: 12 tests; successful commit/reopen, hash/payload and SQLite properties included. Previously run focused SQLite plus dispatcher selection: 23 tests passed. | Tests use temporary test directories, not ProgramData under a service account. |
| SQLite recovery after actual service restart | **BLOCKED** | No database was created at the service data path and no SCM restart occurred. | Requires account, WinSW, ACL and a real pending result in a disposable service data directory. |
| Live worker-to-backend submission | **BLOCKED** | Backend actual result endpoint integration tests ran under Testcontainers; worker dispatcher tests ran separately against a local HTTP server. | No worker process submitted to a live backend. |
| Backend acknowledgement validation | **PASS** (component tests) | Worker dispatcher tests cover acknowledgement fields/identity, malformed response, arbitrary 409 and 401. | No live worker/backend connection. |
| Dropped-response recovery | **BLOCKED** (live end-to-end); **PASS** (component test only) | `ResultDispatcherTest.lostAcknowledgementReplaysExactPayloadAndRecoversMatchingReceipt` passed using its deterministic local HTTP server. | No test proxy dropped a response after a real backend commit. Must not treat component simulation as end-to-end proof. |
| Idempotent backend acceptance | **PASS** (backend integration) | Backend `WorkerResultDeliveryTest` ran in PostgreSQL Testcontainers and verifies matching duplicate receipt behavior. | Not combined with the worker’s actual retry. |
| Worker regression suite | **PASS WITH SKIP** | Exact command: `mvn -s .pr6-temporary-maven-settings.xml -B '-Duser.timezone=UTC' clean verify` from `worker-runtime`; exit code 0, 107 tests, 0 failures, 0 errors, 1 skipped. `PowerShellRealAgentWindowsTest` passed in this run. The real DPAPI round trip passed; the skipped case was the Windows-inapplicable `otherPlatformsFailClearly`. | This does not exercise the intended service identity or service restart. Earlier sandbox run had 1 failure (local query access denied) and 1 DPAPI error; this latest run did not reproduce the endpoint query failure. |
| Backend Maven verification | **PASS** | Exact command: `mvn -s .pr6-temporary-maven-settings.xml -B '-Duser.timezone=UTC' clean verify` from `backend`; exit code 0; 549 tests, 0 failures, 0 errors, 0 skipped. Testcontainers connected to Docker Desktop and used PostgreSQL 16. | Testcontainers verifies backend behavior, not live worker delivery. |
| Focused SQLite/outbox and dispatcher suite | **PASS** | Earlier focused run: 23 tests, 0 failures/errors/skips. Current full worker run also covered 12 SQLite and 11 dispatcher tests. | Component-level evidence only. |
| PowerShell/XML deployment static validation | **PASS** | Parsed all 13 `deploy/*.ps1` files using PowerShell Parser; 0 parse errors. Parsed worker WinSW XML. | Static validation does not validate WinSW schema/version behavior or Windows ACL/SCM operations. |
| Frontend | **NOT RUN** | No frontend files changed. | Not applicable. |

Temporary Maven settings and the two workspace Maven caches used for the reruns were removed. Maven `target` directories are ignored build outputs. No test was counted as a pass when skipped.

## C. Deployment risks

| Risk | Level | Impact and required action |
|---|---|---|
| Service account and profile not provisioned | **HIGH** | The deployment design selects local `svc-posture-worker`, but no actual account exists here. An administrator must provision it, grant service logon, ensure profile loading, and verify the service’s SCM StartName. |
| DPAPI CurrentUser continuity | **HIGH** | Credentials only decrypt under the protecting Windows user/profile. Enroll under the selected identity and prove decryption across an actual SCM restart before enabling production collection. |
| WinSW binary absent | **HIGH** | Service installation and real restart cannot proceed. Obtain the approved x64 WinSW v2.12.0 release binary outside source control, verify its approved SHA-256, and record ProductVersion/hash on the target. |
| Filesystem ACLs not applied or verified | **HIGH** | Admin must inspect and apply ACLs for install/data/log paths. Ensure SQLite `-wal`/`-shm` sidecars and state file are accessible only to the service identity and administrators. |
| Local account management/rotation | **MEDIUM** | Organization must define password rotation and update SCM credentials without breaking DPAPI/profile use. No password is hardcoded or automatically set by the scripts. |
| Endpoint query permissions | **MEDIUM** | Latest full worker suite passed the real-agent Windows test, while an earlier restricted run had Access denied. Validate the exact collector permissions under the selected service identity on representative endpoints. |
| SQLite backup, retention and disk capacity | **MEDIUM** | Define protected backup/restore and free-space monitoring. Back up DB and WAL/SHM coherently while stopped; never delete unresolved results to recover space. |
| Backend reachability and auth | **HIGH** | Live worker connectivity is untested. Validate disposable enrollment, network reachability, TLS and worker authentication against the disposable backend. |
| Prolonged outage, retry and lease operations | **MEDIUM** | Component tests establish bounded retry behavior; no service-level backlog or prolonged outage was run. Monitor pending age/count and lease-renewal outcomes. |
| Service logs and secret exposure | **MEDIUM** | Paths and restricted ACL intentions are defined, but no real service logs were produced. Inspect sanitized logs after deployment; verify no credential, enrollment token, or sensitive payload is present. |
| Response-drop recovery through real backend | **HIGH** | Critical end-to-end acceptance remains outstanding. Run a test-only proxy that drops the first committed response, then verify byte-identical retry, valid duplicate receipt, a single backend result, and delivered outbox state. |

### Administrator actions still required

1. Obtain and independently verify the approved WinSW v2.12.0 binary/hash.
2. Provision local `svc-posture-worker` (or revise the deployment identity through an explicit decision), grant service logon, and ensure its profile loads. Do not use backend `svc-posture`.
3. Stage a clean worker package and disposable backend origin; inspect and apply the install/data/log ACL script.
4. Enroll a disposable worker credential under the actual service identity; verify DPAPI decrypts after SCM stop/start.
5. Install/start/stop/restart the isolated service and retain a pending result across an actual JVM restart; compare payload bytes/hash and completion state.
6. Run worker-to-disposable-backend delivery and response-drop/retry through a test-only proxy, including 401, arbitrary 409 and mismatched-ack rejection.
7. Record sanitized service identity, logs, ACLs, outbox state and backend receipt/count evidence.

## D. PR6 completion decision

**PR6 PARTIALLY VERIFIED**

Existing automated evidence: backend tests (549 passing), worker suite (107 tests, 0 failures/errors, 1 skipped), focused SQLite/dispatcher tests (23 passing), and backend/component idempotency tests. The real DPAPI round trip and real-agent PowerShell test passed in the latest worker run under the interactive sandbox identity. The skipped test is the Windows-inapplicable `otherPlatformsFailClearly`. Earlier runs had a DPAPI profile-not-loaded error and an endpoint-query access-denied failure. The service-account DPAPI test remains unresolved because the intended account is absent and no SCM restart was exercised.

New deployment work: a worker-only WinSW template, deployment scripts and operations guide have been authored and statically parsed. No new Windows service-level or live end-to-end evidence exists: the worker account/profile, approved WinSW executable and ACLs are absent. SQLite/DPAPI SCM restart recovery, live worker delivery and the real backend response-drop test remain blocked. PR6 cannot be called operationally verified until these critical checks pass under the selected worker identity.

## E. Git state at initial operational report capture

- Branch: `feature/pr6-operational-readiness`.
- HEAD at the initial operational test/report capture: `8f58b941925dc52687f14af10112a3b896e2b426`. The final review is a subsequent documentation/deployment-only change on this branch.
- Production Java/business logic, migrations, credentials and installed services changed: none.
- Files changed during the initial operational-readiness work: `worker-runtime/README.md`; `deploy/Configure-WorkerServiceAcl.ps1`; `deploy/EndpointPostureWorker.xml`; `deploy/Initialize-PostureWorkerCredentials.ps1`; `deploy/Install-PostureWorkerService.ps1`; `deploy/Prepare-PostureWorkerDeployment.ps1`; `deploy/Provision-WorkerServiceAccount.ps1`; `deploy/Remove-PostureWorkerService.ps1`; `deploy/Start-PostureWorkerService.ps1`; `deploy/Stop-PostureWorkerService.ps1`; `deploy/WORKER-DEPLOYMENT.md`; and this report. The untracked `.agents/` directory is present in the working tree and was not modified.
- Temporary Maven settings were removed. Generated Maven caches created for these runs were removed; Maven `target` outputs are ignored build artifacts.
- At the initial operational report capture, no commit, push, merge or PR had been created.

`git diff --stat` output (Git does not include untracked files in this statistic):

```text
 worker-runtime/README.md | 80 +++++++++++++++++++++++-------------------------
 1 file changed, 39 insertions(+), 41 deletions(-)
```

`git status --short --branch` output at initial operational report capture:

```text
## feature/pr6-operational-readiness
 M worker-runtime/README.md
?? .agents/
?? deploy/Configure-WorkerServiceAcl.ps1
?? deploy/EndpointPostureWorker.xml
?? deploy/Initialize-PostureWorkerCredentials.ps1
?? deploy/Install-PostureWorkerService.ps1
?? deploy/Prepare-PostureWorkerDeployment.ps1
?? deploy/Provision-WorkerServiceAccount.ps1
?? deploy/Remove-PostureWorkerService.ps1
?? deploy/Start-PostureWorkerService.ps1
?? deploy/Stop-PostureWorkerService.ps1
?? deploy/WORKER-DEPLOYMENT.md
?? docs/
```






## F. Final review static checks

- Reviewed the worker deployment scripts, WinSW XML, worker README, operational report, all frontend design-review Markdown files, and the standalone HTML mockup. The installer and start script previously used a substring match for the SCM account; both now require the configured local account in exact machine-qualified or `.`-qualified form (PowerShell string comparison is case-insensitive). No account/service/ACL operation was executed.
- PowerShell syntax command: `[System.Management.Automation.Language.Parser]::ParseFile(...)` for every `deploy/*.ps1`; 13 scripts parsed, 0 parser errors.
- WinSW XML command: `[xml](Get-Content deploy/EndpointPostureWorker.xml -Raw)`; parsed successfully with service ID `EndpointPostureWorker`.
- HTML anchor/static-asset check: Python regex-based local reference scan on `docs/frontend/design-review/mockups/index.html`; 15 IDs, 25 local fragment references, 0 unresolved references, 0 external asset references. The placeholder endpoint links were changed to the endpoint-card anchor and core light-theme tokens now match `DESIGN-SYSTEM.md`.
- Markdown relative-link check: checked the worker guide, worker README, operational report and all seven design-review Markdown files; 10 files, 0 broken relative links.
- Browser rendering: **NOT RUN**. No Edge/Chrome/Firefox executable or browser automation tool was available in this environment. Static structure/anchor checks do not prove rendered layout.
- No Maven suite was rerun during this final documentation/deployment review. The backend and worker Maven results above are from the previously recorded operational verification run; this review changed only deployment scripts and documentation, not Java application source or tests.
