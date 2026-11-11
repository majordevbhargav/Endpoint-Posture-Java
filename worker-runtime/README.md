# Windows worker runtime

`worker-runtime` is a standalone Java 21 process for Windows endpoint collection. It enrolls against the configured backend, stores its worker key using Windows DPAPI `CurrentUser`, heartbeats, claims work only when the PowerShell execution handler is enabled, and persists completed results in a local SQLite outbox before reporting handoff success.

## Build and local run

```powershell
mvn -B clean verify
$env:EP_BACKEND_URL = 'https://<backend-host>'
$env:EP_ENROLLMENT_TOKEN = '<single-use enrollment token>'
java -jar target\worker-runtime-0.1.0-SNAPSHOT.jar
```

For a local development backend only, the worker permits `http://localhost` or `http://127.0.0.1`. Non-loopback origins must use HTTPS. Enrollment tokens are single-use; after enrollment remove them from the process environment. The API key is stored only in DPAPI protected form.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `EP_BACKEND_URL` | Required | Backend origin only; the worker refuses a different origin from the one associated with stored credentials. |
| `EP_ENROLLMENT_TOKEN` | None | Single-use enrollment token, read from process environment only. |
| `EP_WORKER_STATE_FILE` | `%ProgramData%\EndpointPostureWorker\state.json` on Windows | DPAPI protected worker identity and key. |
| `EP_WORKER_DATA_DIRECTORY` | Parent directory of the state file | Persistent SQLite outbox directory. Database file is `worker-results.sqlite`. |
| `EP_POWERSHELL_EXECUTION_ENABLED` | `false` | Opts into PowerShell job collection. Enable only after endpoint rights and script contracts are reviewed. |
| `EP_SCRIPTS_DIRECTORY` | `scripts` relative to working directory | Collector PowerShell scripts. |
| `EP_POWERSHELL_EXE` | `powershell.exe` | Windows PowerShell executable. |
| `EP_HTTP_CONNECT_TIMEOUT_SECONDS` / `EP_HTTP_REQUEST_TIMEOUT_SECONDS` | `10` / `30` | HTTP timeout limits. |
| `EP_RESULT_DELIVERY_GRACE_MINUTES` | `15` | Absolute result-delivery lease-renewal grace. |
| `EP_RESULT_MAX_CUMULATIVE_LEASE_EXTENSION_SECONDS` | `900` | Maximum cumulative lease extension across retries/restarts. |

## Windows service deployment

Use the dedicated worker WinSW configuration and reviewed administrator workflow in [deploy/WORKER-DEPLOYMENT.md](../deploy/WORKER-DEPLOYMENT.md). The deployment design selects a separate local account named `svc-posture-worker`; it does not reuse the backend `svc-posture` account. An administrator must provision that account, its service-logon right, ACLs and the approved WinSW binary. Do not start the worker under a different account from the one used to enroll/protect credentials.

The WinSW XML template is `deploy/EndpointPostureWorker.xml`. Its service starts in Manual mode until the installer confirms the service identity; it then sets Automatic startup but leaves the service stopped. Credential initialization under that same service identity must be complete before installation. Log output is configured under `%ProgramData%\EndpointPostureWorker\logs`; credential state and SQLite outbox are in `%ProgramData%\EndpointPostureWorker`. The worker identity receives modify access to those private locations and read/execute access to the staged JAR and scripts. SYSTEM and local Administrators retain administrative access.

## Credential and outbox protections

- DPAPI uses `CurrentUser`, not `LocalMachine`. The Windows account that enrolls/protects the key must be the same account that runs the worker service, with its user profile available.
- `state.json` contains the DPAPI protected key. Never copy it to another account or machine and never place plaintext keys, enrollment tokens or DPAPI data in SQLite.
- SQLite uses ordered schema migrations, WAL, `synchronous=FULL`, a busy timeout and foreign-key enforcement. The database and WAL/SHM sidecars must stay on persistent local storage.
- A result is not handed off successfully until its immutable serialized request is committed. The outbox preserves claim/fencing identity, hashes payload bytes, and verifies integrity on load.
- The dispatcher sends the exact stored payload, validates the backend acknowledgement, retries transient failures with bounded backoff, and keeps unresolved/permanent results. Unresolved results continue to block further claims.
- WinSW shutdown sends the worker a stop signal and waits according to its configured timeout. Always verify the Java process exits before copying or backing up outbox files.
- Logs must not contain API keys or enrollment tokens. Restrict access to logs because worker diagnostics and endpoint information may still be sensitive.

The service deployment and live backend acceptance flow have not been verified on a machine with a provisioned worker identity and approved WinSW binary. Automated SQLite/dispatcher tests do not replace Windows SCM restart and live end-to-end verification.
