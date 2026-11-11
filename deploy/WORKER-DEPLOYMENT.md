# Windows worker service deployment

This deployment is separate from the Linux backend service in `endpoint-posture.xml`. It uses a dedicated worker service wrapper and a distinct local Windows account. No account, password, ACL, service or machine setting is created by this document; an administrator must perform the listed actions on each worker host.

## Selected identity and privileges

The repository-supported deployment model creates a dedicated local account named **`svc-posture-worker`** on each Windows worker machine. Its service identity is `COMPUTERNAME\svc-posture-worker`. This is a selected deployment design, not evidence that the account exists or is installed on a machine. It is separate from the backend’s `svc-posture` account. Do not run the Windows worker as LocalSystem, an interactive operator, or the backend account.

The account needs:

- The Windows **Log on as a service** right.
- Read and execute access to the worker installation directory and collector scripts.
- Modify access to `%ProgramData%\EndpointPostureWorker` and its `logs` directory. This includes `state.json`, `worker-results.sqlite`, and SQLite WAL/SHM sidecars.
- No local Administrators membership. Add only endpoint-specific WMI/CIM/WinRM permissions that the approved collector scripts demonstrably require.
- A Windows user profile. Credentials are DPAPI `CurrentUser` protected; perform first enrollment interactively under this same account using a profile-enabled logon. Microsoft documents that SCM automatically loads the profile when it logs on a service user account; confirm this on the target host as part of acceptance. Do not change DPAPI to `LocalMachine`. See [Microsoft service user accounts](https://learn.microsoft.com/en-us/windows/win32/services/service-user-accounts).

`Provision-WorkerServiceAccount.ps1` prompts for a new password as a `SecureString`, creates only the named local user if it does not already exist, and grants the local service-logon right through the local user-rights policy. It refuses an existing account. Domain Group Policy can replace local rights; verify the effective policy after provisioning. The account password is never hardcoded. The organization must set and operate its password rotation policy; the SCM logon credential must be updated during rotation and the service restart verified.

## WinSW package and command compatibility

The worker wrapper is pinned to **WinSW v2.12.0** so its XML and commands match the existing `deploy/Install-PostureService.ps1` v2 convention. The worker binary is not included in this repository. Obtain the approved x64 .NET Framework 4.6.1 binary from the [official WinSW v2.12.0 release](https://github.com/winsw/winsw/releases/tag/v2.12.0); the release asset URL is `https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW.NET461.exe`. Verify it against the organization’s independently approved SHA-256 record before staging. The scripts reject a different ProductVersion or a digest mismatch and do not download the executable.

WinSW v2 documentation lists `install`, `start`, `stop`, `stopwait`, `restart`, `status`, and `uninstall`; the repository’s backend installer also uses `install` and `start`. The worker scripts use only the documented `install` and `uninstall` commands; routine start/stop/restart use Windows SCM PowerShell commands. See the [WinSW v2 command list](https://github.com/winsw/winsw/blob/v2/README.md), [v2 XML configuration](https://github.com/winsw/winsw/blob/v2/doc/xmlConfigFile.md), and [v2 log configuration](https://github.com/winsw/winsw/blob/v2/doc/loggingAndErrorReporting.md). The installed binary version must still be recorded on the target host; no WinSW binary was present in this checkout.

## Paths and configuration

The staged default paths are:

| Purpose | Path | Service account access |
|---|---|---|
| Installation/JAR/scripts/config | `%ProgramFiles%\EndpointPostureWorker` | Read and execute |
| Protected worker state | `%ProgramData%\EndpointPostureWorker\state.json` | Modify |
| SQLite outbox | `%ProgramData%\EndpointPostureWorker\worker-results.sqlite` | Modify |
| SQLite WAL/SHM and other DB sidecars | Same data directory | Modify |
| stdout/stderr, bootstrap and wrapper logs | `%ProgramData%\EndpointPostureWorker\logs` | Modify |

The service XML template is [`EndpointPostureWorker.xml`](EndpointPostureWorker.xml). Package preparation replaces its explicit placeholders with the machine’s absolute Java 21 path, backend origin, and data/log paths. It starts in Manual mode; the installer changes it to Automatic only after it configures and verifies the service identity. The installer does not start the service.

PowerShell execution is disabled in the template. Enable `EP_POWERSHELL_EXECUTION_ENABLED` only after endpoint permissions, script paths, output contracts and the selected identity have been reviewed. No backend or worker credential is stored in the service XML. `EP_BACKEND_URL` must be an origin without user information, path, query or fragment; non-loopback HTTP is rejected by the worker.

The ACL script replaces ACLs only beneath the explicitly named install, data and log trees. It preserves access for SYSTEM and local Administrators, grants the worker account the minimum access above, and removes inherited/unlisted access. Inspect paths and existing ACLs before running it. It refuses reparse points. It is a separate administrator action and is not invoked by staging or service installation.

## Administrator workflow

Run from an elevated PowerShell session where indicated. Start with a clean, reviewed worker build and use a disposable backend/worker enrollment token for acceptance testing.

1. Build/package the worker on the build host and copy the resulting shaded `worker-runtime-0.1.0-SNAPSHOT.jar`, repository `scripts` directory, deployment XML and approved WinSW v2.12.0 binary to the worker host. Do not put the WinSW binary into source control.
2. Provision the local account and service-logon right (elevated):

   ```powershell
   .\deploy\Provision-WorkerServiceAccount.ps1
   ```

   If local policy cannot set the right, use Local Security Policy or the controlling domain GPO to grant **Log on as a service** to `COMPUTERNAME\svc-posture-worker`. Do not grant administrative membership.

3. Prepare a new installation. `InstallDirectory` and `DataDirectory` must not already exist; staging refuses to overwrite them. Supply the backend origin and the hash from the separately approved binary record:

   ```powershell
   .\deploy\Prepare-PostureWorkerDeployment.ps1 `
     -BackendUrl 'https://<disposable-backend-host>' `
     -WinSWPath 'C:\Staging\WinSW.NET461.exe' `
     -ExpectedWinSWSha256 '<64-hex-approved-digest>'
   ```

4. Apply ACLs as an administrator; inspect the resulting ACLs before continuing:

   ```powershell
   .\deploy\Configure-WorkerServiceAcl.ps1 `
     -ServiceAccount "$env:COMPUTERNAME\svc-posture-worker" `
     -InstallDirectory "$env:ProgramFiles\EndpointPostureWorker" `
     -DataDirectory "$env:ProgramData\EndpointPostureWorker" `
     -LogDirectory "$env:ProgramData\EndpointPostureWorker\logs"
   icacls "$env:ProgramFiles\EndpointPostureWorker"
   icacls "$env:ProgramData\EndpointPostureWorker"
   icacls "$env:ProgramData\EndpointPostureWorker\logs"
   ```

5. Load the service account’s profile by starting a profile-enabled shell under the same account. Windows prompts for the account password; do not put it in a command:

   ```powershell
   runas /profile /user:"$env:COMPUTERNAME\svc-posture-worker" powershell.exe
   ```

   In that account’s shell, run credential initialization from the staged source/deployment tools. The script prompts securely for a one-time enrollment token, starts the worker only long enough to save the DPAPI-protected state, clears the token from its environment, and then terminates that temporary JVM. It does not start or install the Windows service:

   ```powershell
   Set-Location "$env:ProgramFiles\EndpointPostureWorker"
   .\Initialize-PostureWorkerCredentials.ps1
   ```

   Verify the resulting state file contains a protected value only; do not print or copy its value. If DPAPI reports that the profile is unavailable or the enrollment token was spent but the state was not saved, stop. Have an administrator resolve profile/logon configuration and revoke/reissue the disposable enrollment token as needed. Never substitute another user.

6. From an elevated shell, install the service. The script prompts for the service password securely using PowerShell’s credential API, configures SCM before enabling Automatic startup, verifies the service identity, and leaves the service stopped. Credentials must already be initialized under that identity:

   ```powershell
   .\deploy\Install-PostureWorkerService.ps1 `
     -ExpectedWinSWSha256 '<64-hex-approved-digest>'
   sc.exe qc EndpointPostureWorker
   ```

   Confirm `SERVICE_START_NAME` is `COMPUTERNAME\svc-posture-worker`, not LocalSystem or `svc-posture`.

7. Start and verify through SCM:

   ```powershell
   .\deploy\Start-PostureWorkerService.ps1
   Get-Service EndpointPostureWorker
   Get-CimInstance Win32_Service -Filter "Name='EndpointPostureWorker'" |
     Select-Object Name, StartName, State, ProcessId, PathName
   ```

8. Stop and verify a genuine process exit before capturing the database or testing recovery:

   ```powershell
   .\deploy\Stop-PostureWorkerService.ps1
   Get-Service EndpointPostureWorker
   Get-CimInstance Win32_Service -Filter "Name='EndpointPostureWorker'" |
     Select-Object Name, StartName, State, ProcessId
   ```

9. For removal, run `Remove-PostureWorkerService.ps1 -ExpectedWinSWSha256 '<approved-digest>'`. It stops/unregisters only this service and retains the credential state, outbox, WAL/SHM files and logs. Do not delete unacknowledged outbox entries. Retire data only after an explicit retention decision and secure backup/handling review.

## Credential rotation and recovery

DPAPI credential state is separate from SQLite. Do not copy `state.json` to another Windows account or machine and expect it to decrypt. Credential rotation is not an automated worker API operation: use the backend’s existing revoke/re-enrollment process under the same service identity, stop the service before replacing local state, and preserve unresolved results. If account password rotation occurs, update the SCM credential with the service stopped, verify `StartName`, then start/recover the worker under that account. No automated process decrypts credentials for diagnostics.

SQLite uses WAL and FULL synchronous commits. Back up only while stopped, capturing the database and any `-wal`/`-shm` files together; store backups encrypted with access limited to the worker administrators. Monitor free space, pending count/age and log volume. No unresolved result is automatically pruned.

## Acceptance runbook and current verification limit

Before enabling collection, execute the actual service test under `svc-posture-worker`: stop it using SCM, verify its Java PID exits, retain one pending result, start it again, compare the stored request bytes/hash, and verify one backend receipt and delivered outbox state. Repeat with a test-only reverse proxy configured as the worker’s loopback backend origin: forward the first result POST to the disposable backend, allow the backend commit, discard only the response, then forward the retry and relay the backend’s original duplicate receipt. Confirm byte-identical payloads, exactly one backend receipt/evidence application, and outbox completion. Exercise 401, mismatched acknowledgement and arbitrary 409 as retained failures.

The local HTTP component test is not a substitute for that test. This checkout has no provisioned worker identity, WinSW binary, installed worker service, or accessible worker data directory, so service/DPAPI restart/live backend acceptance has not yet been executed. The required administrator setup and acceptance evidence remain outstanding.
