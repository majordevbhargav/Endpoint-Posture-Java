# worker-runtime

Independent Windows worker for the VE Compliance Engine (PR6). This runtime enrolls the worker, stores its key with Windows DPAPI, and then runs a persistent
heartbeat lifecycle. Heartbeat cadence is supplied by the backend; transient network/server
failures use bounded exponential backoff with jitter, HTTP 429 honors delta-seconds or HTTP-date Retry-After values, and HTTP 401 stops the
worker because its credentials may have been revoked. Shutdown interrupts the wait loop cleanly.
Job claiming is wired behind a handler boundary and remains disabled in the production entry point until PR6.3 provides a real
executor, so this version cannot lease work it cannot execute. Retry delays cap at five minutes; transient HTTP 408, 425, 429 and 5xx responses are retried, while authentication failures and permanent client errors stop the worker.

## Lifecycle constraints for PR6.3

The current handler interface is synchronous and is only a test seam in PR6.2. Do not connect a blocking PowerShell executor directly to this lifecycle thread: a long-running handler would prevent subsequent heartbeats and could allow the backend to mark the worker stale. PR6.3 must run job execution independently from the control-plane heartbeat loop, use bounded queues/coordination, and propagate cancellation and worker shutdown to the executor. PR6.4 must coordinate lease renewal with that executor and fence all results by the current worker/job attempt. No PowerShell execution or lease renewal is implemented in PR6.2.

Lease renewal and result reporting
are added in PR6.4; Windows service installation and service-account ACLs are added in PR6.5.

## Run

    mvn -B clean verify
    $env:EP_BACKEND_URL       = "https://<backend>:8090"
    $env:EP_ENROLLMENT_TOKEN  = "<ept_... from POST /api/v1/admin/workers/enrollment-tokens>"
    java -jar target\worker-runtime-0.1.0-SNAPSHOT.jar

After enrollment the token is no longer needed. Remove `EP_ENROLLMENT_TOKEN` afterward.

| Variable | Default | Meaning |
|---|---|---|
| `EP_BACKEND_URL` | required | scheme, host and port only |
| `EP_WORKER_STATE_FILE` | `%ProgramData%\EndpointPostureWorker\state.json` | holds the DPAPI-protected key |
| `EP_WORKER_DATA_DIRECTORY` | `%ProgramData%\EndpointPostureWorker` | persistent directory for the SQLite result outbox |
| `EP_ENROLLMENT_TOKEN` | none | single-use; read from the environment only |
| `EP_HTTP_CONNECT_TIMEOUT_SECONDS` / `EP_HTTP_REQUEST_TIMEOUT_SECONDS` | 10 / 30 | |
| `EP_RESULT_DELIVERY_GRACE_MINUTES` | 15 | maximum period during which the worker renews a pending result lease |
| `EP_RESULT_MAX_CUMULATIVE_LEASE_EXTENSION_SECONDS` | 900 | maximum requested lease extension across delivery attempts |

## Security notes
- The key is DPAPI-protected (current user). Run the worker as the same Windows account that enrolled.
- Credential state files are limited to 64 KiB; symlink and non-regular state paths are rejected.
- DPAPI subprocess input is limited to 16 KiB; captured stdout and stderr are limited to 64 KiB and 16 KiB.
- Worker API response bodies are limited to 1 MiB.
- Restrict the state directory's ACL to the Windows service account during installation (PR6.5).
- The worker stores results in `worker-results.sqlite` under `EP_WORKER_DATA_DIRECTORY` using SQLite WAL and full synchronous commits. SQLite and its WAL/SHM sidecars must remain on persistent local storage and accessible to the worker account.
- The repository does not select a worker service identity. Configure the service with a dedicated account, run enrollment under that same account (DPAPI CurrentUser scope), and run `Configure-WorkerDataDirectory.ps1 -ServiceAccount <DOMAIN\account>` elevated to grant access to the data directory. Do not assume the backend's `svc-posture` identity is the worker identity.
- The outbox preserves result request bytes and retries after restart. A result is marked delivered only after a matching backend receipt acknowledgement; permanent failures remain stored and block further job claims for operator review.
- The key is only ever sent to the origin it enrolled with; a different `EP_BACKEND_URL` is refused.
- HTTP redirects are never followed.
- If enrollment succeeds but the key cannot be saved, the token is spent: an admin must revoke the worker and issue a new token.

For local development only, `http://127.0.0.1` and `http://localhost` are accepted. Non-loopback backends must use HTTPS.
