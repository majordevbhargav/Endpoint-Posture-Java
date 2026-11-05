# Remote worker authentication (PR4)

PR4 adds individual, revocable credentials for remote Windows workers. It does not deploy workers or change the live AWS environment.

## Lifecycle

1. An administrator creates a worker enrollment token with a worker name, shard and TTL.
2. The plaintext token is returned once. PostgreSQL stores only its SHA-256 hash.
3. The worker redeems the token at `POST /api/v1/workers/enroll`.
4. The response returns the worker ID, name, shard and a random 256-bit API key once. PostgreSQL stores only the key hash.
5. Subsequent worker requests send `X-Worker-Key: epw_...`.
6. An administrator can list or revoke workers. Revocation takes effect on the next authenticated request.

Enrollment tokens expire after at most 24 hours. The default is 60 minutes. Worker names and shard identifiers are restricted to letters, digits, dot, underscore and hyphen.

## Admin API

All admin routes require a human JWT with `ROLE_ADMIN`.

- `POST /api/v1/admin/workers/enrollment-tokens`
  `{"workerName":"win-worker-01","shard":"site-a","ttlMinutes":60}`
- `GET /api/v1/admin/workers`
- `POST /api/v1/admin/workers/{workerId}/revoke`

The enrollment token response contains the plaintext token. Store it securely and deliver it to the intended worker. The token cannot be retrieved later.

## Worker API

- `POST /api/v1/workers/enroll` with `{"token":"ept_..."}`. No worker key is required for this one-time endpoint.
- `POST /api/v1/workers/jobs/claim`
- `POST /api/v1/workers/jobs/{jobId}/complete` with `{"attempt":1}`
- `POST /api/v1/workers/jobs/{jobId}/fail` with `{"attempt":1,"reason":"..." }`

All routes other than enrollment require `X-Worker-Key`. The worker ID and shard are derived from the credential registry, never accepted from the request body. Claims are restricted in SQL to the authenticated worker's shard and use `FOR UPDATE SKIP LOCKED`.

The claim response contains the job ID, attempt fencing token, job type, typed job specification and lease expiry. Completion and failure require the same worker identity, attempt number and an unexpired lease. Stale, expired or wrong-worker submissions return HTTP 409.

## Security boundaries

- Worker keys and enrollment tokens are generated with 256 bits of cryptographic randomness.
- Only SHA-256 hashes of these high-entropy secrets are stored.
- Enrollment redemption is a single atomic database update, so a token can be used once.
- Worker authentication is rate-limited by source IP. Failed and revoked-key attempts are audited.
- Worker registry responses never include key hashes or plaintext credentials.
- Worker routes require `ROLE_WORKER`; admin routes require `ROLE_ADMIN`.
- Workers are explicitly excluded from all other `/api/v1/**` application routes.
- Existing `X-Posture-Api-Key` ingestion for current PowerShell agents is preserved.
- Existing local in-process worker and simulator paths remain unchanged.
- Worker evidence ingestion is not yet bound to a specific job attempt. This remains the accepted D11 limitation; see `Doc/DECISIONS.md`.

## Lease configuration

`app.workers.lease-seconds` defaults to 300 seconds and can be set with `WORKER_LEASE_SECONDS`. The effective minimum is 30 seconds. A worker must finish and submit its result before the lease expires. PR4 does not yet expose lease renewal.

## Operational caution

Do not expose remote worker claim endpoints to the internet until the backend has been deployed with this PR4 code and each worker has its own enrolled credential. Do not reuse the legacy shared posture agent key as a worker key. PR4 adds the API and credential lifecycle only; it does not provision EC2 instances, install Windows workers, change security groups, or deploy to the live environment.
