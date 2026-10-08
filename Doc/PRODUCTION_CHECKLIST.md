# Production Hardening Checklist

Mandatory configuration and security steps before deploying the VE Compliance Engine beyond a lab. Values below match `application.yml`; if they ever disagree, the YAML wins.

---

## 1. Secrets (no defaults exist; the app refuses to start without them)

Supply at runtime through environment variables or a secret store (Windows machine environment, Vault, Key Vault):

| Variable | Used for |
|---|---|
| `JWT_SECRET` | HMAC-SHA256 signing key, at least 32 bytes of random data |
| `SEED_ADMIN_PASSWORD` | Bootstrap admin password. Used only when `app_user` is empty. Change it right after first login. |
| `POSTURE_API_KEY` | Shared key for the PowerShell agents (`X-Posture-Api-Key`) |
| `DB_PASSWORD` | PostgreSQL password (also `DB_URL`, `DB_USERNAME`) |
| `ISE_BASE_URL`, `ISE_USERNAME`, `ISE_PASSWORD` | Cisco ISE (ERS + MNT) |

- Any value that appeared in an older handoff or chat (lab admin password, agent key, DB password, ISE user) is compromised. Rotate it.
- `application-dev.yml` is git-ignored. Confirm with `git ls-files | findstr application-dev`.
- JWT rotation: issuing a new secret invalidates all sessions. Plan a maintenance window.
- Agent key rotation: there is a single shared key. Change backend and agents together.

---

## 2. Cisco ISE

- `app.ise.verify-tls` MUST be `true`. A startup WARN is logged when it is `false`. Import the ISE CA into the JVM truststore (`-Djavax.net.ssl.trustStore=...`).
- Use dedicated least-privilege ISE accounts: an ERS operator for endpoint/ANC calls, a Monitoring account for MNT sessions.
- Confirm the real ANC policy name before `app.ise.enforcement-mode: ANC` (the default `Quarantine` is a guess, DECISIONS D5).

---

## 3. Network, TLS and proxying

- Terminate TLS 1.2+ at a reverse proxy (nginx, Caddy, IIS ARR). Close port 8090 to everyone except the proxy and the agents.
- The frontend proxies `/api/*` to the backend, so the backend sees the proxy's IP, not the user's. The login rate limiter (`app.security.login.rate-limit.*`, default 10 per minute per IP) therefore shares one bucket across all users. Set `app.security.trust-forwarded-for: true` ONLY when the backend is reachable solely through a proxy that overwrites `X-Forwarded-For`. Never set it on a directly exposed backend; clients could forge the header and dodge the limit.
- Backend headers: `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy`, `Cache-Control: no-store` on `/api/**`. The frontend sets the same plus a CSP. The CSP currently allows `'unsafe-inline'` and `'unsafe-eval'` for Next.js; tighten with nonces later.

---

## 4. API documentation

- `app.docs.public` MUST be `false` (default). Swagger UI and `/v3/api-docs` then require `ROLE_ADMIN`. Only `application-dev.yml` sets it `true`. A startup WARN is logged otherwise.

---

## 5. Agents and permissions (DECISIONS D7)

- Ingestion routes (`/api/v1/posture`, `/hardware-health`, `/diagnostics`, `/security-indicators`) accept the agent key (`ROLE_AGENT`) or an admin JWT and never call ISE.
- Share, Restrict and Clear need `ADMIN`/`OPERATOR` (share also `ANALYST`) and are audited.
- Replace the shared admin credential in `posture_common_cred.xml` with a least-privilege service account that has only WMI/WinRM read rights on endpoints.

---

## 6. Windows service deployment (DECISIONS D2, Option A)

- Run the jar on a managed Windows Server with JDK 21 as a service (WinSW or NSSM).
- The DPAPI credential file can only be decrypted by the account that saved it. Run `Save-PostureCredential.ps1` while logged on as the service account, then run the service as that same account. A gMSA cannot log on interactively, so use a normal dedicated service user, or create the file through a one-time scheduled task running as that account.
- Required inside the service environment: the secrets from section 1, and `-Duser.timezone=UTC`.
- WinSW sketch (`endpoint-posture.xml`):

```xml
<service>
  <id>EndpointPostureEngine</id>
  <name>VE Endpoint Posture Engine</name>
  <description>Endpoint posture, telemetry collection and ISE bridge</description>
  <executable>java</executable>
  <arguments>-Xms1g -Xmx2g -Duser.timezone=UTC -jar endpoint-posture-java-0.1.0-SNAPSHOT.jar</arguments>
  <log mode="roll-by-size">
    <sizeThreshold>10240</sizeThreshold>
    <keepFiles>10</keepFiles>
  </log>
</service>
```

- Firewall: outbound to endpoint VLANs (WinRM 5985, DCOM/RPC) and ISE (443). For workgroup targets reached by IP, add them to the host's WinRM `TrustedHosts`.

---

## 7. PostgreSQL

- PostgreSQL 16 with the `pg_trgm` extension (created by migration V22, so the DB user needs permission to create it, or an admin must create it first).
- Pool defaults (`application.yml`): `maximum-pool-size` 10 (`DB_POOL_SIZE`), `connection-timeout` 15 s, `keepalive-time` 2 min, `max-lifetime` 15 min, `tcpKeepAlive=true`. Raise the pool when running many worker threads (the simulator uses 30 for 40 threads).
- Daily backups (pg_dump or pgBackRest) and a tested restore. Assessments, check results and audit rows are evidence and are never pruned; plan disk for them.
- Retention that exists: finished jobs after 30 days, inventory payloads beyond the newest 10 runs per endpoint.

---

## 8. Before go-live, verify by hand (an agent cannot do these)

Diagnostics, security scan, warranty upload, account lockout, the `/ise-actions` page, and the frontend container, each against a real endpoint and ISE.