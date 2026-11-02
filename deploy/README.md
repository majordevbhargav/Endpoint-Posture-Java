# Deployment kit (DECISIONS D2 Option A, D7)

> See `Doc/DECISIONS.md` D2 and `Doc/PRODUCTION_CHECKLIST.md` for background.

Target: one Windows Server VM running JDK 21, Docker (Postgres), the backend as a service, Caddy in front. UNTESTED until run on your VM.

Order:
1. Copy repo build to `C:\PostureEngine` (`backend\*.jar`, `scripts\`, WinSW as `EndpointPostureEngine.exe`, `endpoint-posture.xml`).
2. `New-PostureServiceAccount.ps1` (admin). Also grant that account read-only WMI/WinRM rights on target endpoints (not domain admin).
3. `runas /user:svc-posture powershell` then `scripts\Save-PostureCredential.ps1`.
4. `Set-PostureSecrets.ps1`, then `docker compose up -d postgres`.
5. `Install-PostureService.ps1`.
6. Caddy with `proxy\Caddyfile`; firewall 8090 and 3000 to localhost; open 443 only. Run the frontend container (`docker compose up -d --build frontend`).
7. `Test-Deployment.ps1 -Base https://your-host`.
8. Schedule `Backup-Postgres.ps1` daily; do one restore test.
9. Point ISE pxGrid Direct at `/api/v1/integrations/ise/endpoints` with the feed credential (limits still unchecked).

> **Known issue in `Test-Deployment.ps1` (script bug, not doc-only):** line 5 uses `"$Base/api/../actuator/health"`. The path traversal resolves to `$Base/actuator/health`, which is NOT routed through Caddy (Caddy only proxies `/api/*` and the frontend). The actuator health endpoint is at `http://localhost:8090/actuator/health` on the backend directly, or through the proxy as `$Base/api/actuator/health` if Caddy is configured to pass that prefix. Fix the script before using it in production.
