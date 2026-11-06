# worker-runtime

Independent Windows worker for the VE Compliance Engine (PR6). This slice (PR6.1) enrolls the
worker, stores its key with Windows DPAPI, and proves the key with one heartbeat. Heartbeat and
claim loops, agent execution, lease renewal and result reporting come in PR6.2 to PR6.5.

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
- Restrict the state directory's ACL to that account.
- The key is only ever sent to the origin it enrolled with; a different `EP_BACKEND_URL` is refused.
- HTTP redirects are never followed.
- If enrollment succeeds but the key cannot be saved, the token is spent: an admin must revoke the worker and issue a new token.

For local development only, `http://127.0.0.1` and `http://localhost` are accepted. Non-loopback backends must use HTTPS. Restrict the state directory ACL to the Windows service account during installation (PR6.5).