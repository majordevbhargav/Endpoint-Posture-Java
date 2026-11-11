<# Configure the worker's persistent state/outbox directory for the selected service identity.
   The service must separately be configured to run as this same account. Run as Administrator. #>
param(
    [Parameter(Mandatory=$true)][string]$ServiceAccount,
    [string]$DataDirectory = "$env:ProgramData\EndpointPostureWorker"
)
$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($ServiceAccount)) { throw "A worker service account is required." }
New-Item -ItemType Directory -Force -Path $DataDirectory | Out-Null
& icacls $DataDirectory /inheritance:r | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Could not disable inherited ACLs on $DataDirectory" }
& icacls $DataDirectory /grant:r `
    "$($ServiceAccount):(OI)(CI)F" `
    "SYSTEM:(OI)(CI)F" `
    "BUILTIN\Administrators:(OI)(CI)F" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Could not apply worker data-directory ACLs" }
[Environment]::SetEnvironmentVariable("EP_WORKER_DATA_DIRECTORY", $DataDirectory, "Machine")
Write-Host "Set EP_WORKER_DATA_DIRECTORY=$DataDirectory and restricted access to $ServiceAccount, SYSTEM, and Administrators."
Write-Host "Configure the worker service to run as $ServiceAccount, then enroll/run it under that same account so DPAPI CurrentUser credentials remain accessible."
