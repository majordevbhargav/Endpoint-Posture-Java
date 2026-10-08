# Production Hardening Checklist

This document details mandatory operational requirements, configuration steps, and security guidelines for deploying the VE Compliance Engine to staging and production environments.

---

## 1. Secrets Management and Rotation
- **Never commit secrets**: `application.yml` contains no default values for credentials or keys.
- **Environment variables**: All secrets must be supplied at runtime via environment variables or a secret store (e.g. AWS Secrets Manager, HashiCorp Vault, Azure Key Vault):
  - `APP_JWT_SECRET`: Minimum 256-bit cryptographically secure random string (HMAC-SHA256).
  - `APP_SEED_ADMIN_PASSWORD`: Strong password used only during the initial bootstrap of the `admin` user. Must be changed or disabled immediately post-initialization.
  - `APP_POSTURE_API_KEY`: Strong API key shared with authorized collector agents.
  - `APP_ISE_ERS_PASSWORD` / `APP_ISE_MNT_PASSWORD`: Cisco ISE service account passwords.
  - `SPRING_DATASOURCE_PASSWORD`: PostgreSQL database password.
- **Key Rotation**:
  - JWT secret rotation: generate a new secret; invalidate active sessions or support dual-secret verification during transition.
  - Agent API key rotation: update backend and push new key to endpoints via fleet management (GPO / Intune) before removing old key.

---

## 2. Cisco ISE Integration & TLS Verification
- **Verify TLS (`app.ise.verify-tls`)**:
  - **MUST BE `true` IN PRODUCTION**.
  - Setting `app.ise.verify-tls: false` completely bypasses TLS certificate verification and logs a critical startup warning. Only acceptable in isolated local mock setups.
  - Import ISE CA / server certificates into the Java truststore (`cacerts` or custom truststore via `-Djavax.net.ssl.trustStore`).
- **Least-Privilege ISE Accounts**:
  - Use dedicated read-only/operator accounts on Cisco ISE rather than administrative superusers.
  - ERS account: requires ERS Admin or ERS Operator privileges for ANC and endpoint update calls.
  - MnT account: requires Monitoring Admin privileges for session queries.

---

## 3. Reverse Proxy & Network Architecture
- **TLS Termination**:
  - Terminate TLS 1.3 (or 1.2 minimum) at an enterprise reverse proxy (e.g., NGINX, HAProxy, Envoy, AWS ALB).
  - The Spring Boot backend listens on HTTP localhost/private network only (port 8090 by default).
- **IP Spoofing Protection (`app.security.trust-forwarded-for`)**:
  - Keep `app.security.trust-forwarded-for: false` unless the backend is strictly deployed behind a trusted reverse proxy that strips and sanitizes incoming `X-Forwarded-For` headers from untrusted clients.
  - Never expose the backend directly to external networks with `trust-forwarded-for: true`.
- **Security Headers**:
  - Backend automatically issues `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`, and `Cache-Control: no-store` on all `/api/**` paths.
  - Frontend issues matching headers including a strict Content Security Policy (`frame-ancestors 'none'`).

---

## 4. Documentation & API Visibility
- **Swagger / OpenAPI (`app.docs.public`)**:
  - **MUST BE `false` (default) IN PRODUCTION**.
  - When `false`, `/swagger-ui/**`, `/swagger-ui.html`, and `/v3/api-docs/**` require `ROLE_ADMIN` authentication.
  - Only enable `app.docs.public: true` in local development environments (`application-dev.yml`).

---

## 5. Agent Ingestion & Permissions (DECISION D7)
- **Separate Ingestion from Action**:
  - Agent reporting routes (`/api/v1/posture`, `/api/v1/hardware-health`, `/api/v1/diagnostics`, `/api/v1/security-indicators`) require `ROLE_AGENT` (authenticated via `X-Posture-Api-Key`).
  - Ingestion routes NEVER invoke Cisco ISE actions.
  - Enforcement actions (`/api/v1/ise/enforcement/*`, `/api/v1/ise/posture/share`) are strictly operator/admin authenticated actions (`ROLE_OPERATOR`, `ROLE_ADMIN`).
  - Agents must NEVER possess credentials or permissions for ISE action or policy endpoints.

---

## 6. Windows Service Deployment (DECISION D2 Option A)
- **Deployment Model**:
  - The backend runs on a managed Windows Server instance to support DPAPI, PowerShell agent coordination, and direct network line-of-sight.
  - Run as a Windows Service using **WinSW** (Windows Service Wrapper) or **NSSM**.
  - Configure the service to run under a dedicated managed service account (gMSA) with minimum file system permissions.
  - Sample WinSW configuration (`endpoint-posture.xml`):
    ```xml
    <service>
      <id>EndpointPostureEngine</id>
      <name>VE Endpoint Posture Engine</name>
      <description>Endpoint compliance posture, telemetry collection, and ISE bridge service</description>
      <executable>java</executable>
      <arguments>-Xms1g -Xmx2g -Dspring.profiles.active=prod -jar endpoint-posture-backend.jar</arguments>
      <log mode="roll-by-size">
        <sizeThreshold>10240</sizeThreshold>
        <keepFiles>10</keepFiles>
      </log>
    </service>
    ```

---

## 7. PostgreSQL Database Maintenance & Backups
- **Extensions**:
  - Requires PostgreSQL 16 with `pg_trgm` extension installed and enabled (managed by Flyway migration `V22`).
- **Connection Pool**:
  - Sized for high concurrency (`maximum-pool-size: 20`, `minimum-idle: 5`).
  - Keepalive (`keepalive-time: 30000ms`) and max lifetime (`max-lifetime: 1800000ms`) tuned to prevent firewall TCP socket drops.
- **Scheduled Backups**:
  - Perform daily automated physical backups (WAL-G, pgBackRest) or logical dumps (`pg_dump`).
  - Test restoration procedures periodically in a staging environment.
