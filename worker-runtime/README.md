# worker-runtime

Independent Windows worker for the VE Compliance Engine (PR6). This runtime enrolls the worker, stores its key with Windows DPAPI, and then runs a persistent
heartbeat lifecycle. Heartbeat cadence is supplied by the backend; transient network/server
failures use bounded exponential backoff, HTTP 429 honors Retry-After, and HTTP 401 stops the
worker because its credentials may have been revoked. Shutdown interrupts the wait loop cleanly.
Job claiming is wired behind a handler boundary and remains disabled until PR6.3 provides a real
executor, so this version cannot lease work it cannot execute. Lease renewal and result reporting
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
| `EP_ENROLLMENT_TOKEN` | none | single-use; read from the environment only |
| `EP_HTTP_CONNECT_TIMEOUT_SECONDS` / `EP_HTTP_REQUEST_TIMEOUT_SECONDS` | 10 / 30 | |

## Security notes
- The key is DPAPI-protected (current user). Run the worker as the same Windows account that enrolled.
- Credential state files are limited to 64 KiB; symlink and non-regular state paths are rejected.
- DPAPI subprocess input is limited to 16 KiB; captured stdout and stderr are limited to 64 KiB and 16 KiB.
- Worker API response bodies are limited to 1 MiB.
- Restrict the state directory's ACL to the Windows service account during installation (PR6.5).
- The key is only ever sent to the origin it enrolled with; a different `EP_BACKEND_URL` is refused.
- HTTP redirects are never followed.
- If enrollment succeeds but the key cannot be saved, the token is spent: an admin must revoke the worker and issue a new token.

For local development only, `http://127.0.0.1` and `http://localhost` are accepted. Non-loopback backends must use HTTPS.