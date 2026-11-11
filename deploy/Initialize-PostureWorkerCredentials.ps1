<#
.SYNOPSIS
Enrolls the worker once under its intended service identity without persisting the token.
.DESCRIPTION
Run interactively as svc-posture-worker after its profile and data ACLs are provisioned.
The enrollment token is read as SecureString, passed only in the child process environment,
and cleared from this PowerShell process immediately after launch. The temporary worker JVM
is stopped after the atomic DPAPI-protected state file appears; this is not a service restart.
#>
[CmdletBinding()]
param(
    [string]$InstallDirectory = "$env:ProgramFiles\EndpointPostureWorker",
    [string]$DataDirectory = "$env:ProgramData\EndpointPostureWorker",
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_-]{2,31}$')][string]$ServiceAccount = 'svc-posture-worker',
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
if ($TimeoutSeconds -lt 15 -or $TimeoutSeconds -gt 600) { throw 'TimeoutSeconds must be from 15 through 600.' }
$expectedIdentity = "$env:COMPUTERNAME\$ServiceAccount"
$actualIdentity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
if (-not $actualIdentity.Equals($expectedIdentity,[StringComparison]::OrdinalIgnoreCase)) {
    throw "Run this script as $expectedIdentity. Current identity is $actualIdentity; no enrollment was attempted."
}

$InstallDirectory = [IO.Path]::GetFullPath($InstallDirectory)
$DataDirectory = [IO.Path]::GetFullPath($DataDirectory)
$jar = Join-Path $InstallDirectory 'worker-runtime.jar'
$java = Get-Command java.exe -ErrorAction Stop
$stateFile = Join-Path $DataDirectory 'state.json'
$logDirectory = Join-Path $DataDirectory 'logs'
foreach ($path in @($jar,$DataDirectory,$logDirectory)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Required deployment path is missing: $path" }
}
if (Test-Path -LiteralPath $stateFile) { throw 'Worker state already exists. Refusing to overwrite or rotate credentials.' }
$configPath = Join-Path $InstallDirectory 'EndpointPostureWorker.xml'
$xml = [Xml.XmlDocument]::new()
$xml.Load($configPath)
$backendUrl = $xml.SelectSingleNode('/service/env[@name="EP_BACKEND_URL"]').GetAttribute('value')
if ([string]::IsNullOrWhiteSpace($backendUrl) -or $backendUrl -match '__[A-Z0-9_]+__') { throw 'Staged worker XML has no valid backend origin.' }

$secureToken = Read-Host 'Paste the single-use disposable/production enrollment token for this worker identity' -AsSecureString
if ($secureToken.Length -lt 8) { $secureToken.Dispose(); throw 'Enrollment token is too short; no enrollment attempted.' }
$tokenPointer = [IntPtr]::Zero
$process = $null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outLog = Join-Path $logDirectory "credential-bootstrap-$stamp.out.log"
$errLog = Join-Path $logDirectory "credential-bootstrap-$stamp.err.log"
try {
    $tokenPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
    $plainToken = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($tokenPointer)
    $env:EP_BACKEND_URL = $backendUrl
    $env:EP_ENROLLMENT_TOKEN = $plainToken
    $env:EP_WORKER_STATE_FILE = $stateFile
    $env:EP_WORKER_DATA_DIRECTORY = $DataDirectory
    $env:EP_SCRIPTS_DIRECTORY = Join-Path $InstallDirectory 'scripts'
    $env:EP_POWERSHELL_EXECUTION_ENABLED = 'false'
    $argument = '-jar "' + $jar + '"'
    $process = Start-Process -FilePath $java.Source -ArgumentList $argument -WorkingDirectory $InstallDirectory `
        -PassThru -WindowStyle Hidden -RedirectStandardOutput $outLog -RedirectStandardError $errLog
    Remove-Item Env:EP_ENROLLMENT_TOKEN

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-Path -LiteralPath $stateFile) {
            $stateText = Get-Content -LiteralPath $stateFile -Raw
            if (-not $stateText.Contains($plainToken)) {
                $state = $stateText | ConvertFrom-Json
                if ($state.format -eq 1 -and $state.protectedApiKey) {
                    $plainToken = $null
                    Write-Host "DPAPI-protected worker credentials were written for $expectedIdentity. Worker ID: $($state.workerId)."
                    return
                }
            }
        }
        if ($process.HasExited) { throw "Credential bootstrap JVM exited with code $($process.ExitCode). Inspect its restricted logs; do not copy secrets into diagnostics." }
        Start-Sleep -Milliseconds 250
    }
    throw "Timed out waiting for the protected state file at $stateFile. No service was started."
} finally {
    Remove-Item Env:EP_ENROLLMENT_TOKEN -ErrorAction SilentlyContinue
    $env:EP_BACKEND_URL = $null
    $env:EP_WORKER_STATE_FILE = $null
    $env:EP_WORKER_DATA_DIRECTORY = $null
    $env:EP_SCRIPTS_DIRECTORY = $null
    $env:EP_POWERSHELL_EXECUTION_ENABLED = $null
    $plainToken = $null
    if ($process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        try { $process.WaitForExit(10000) } catch { }
    }
    if ($tokenPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($tokenPointer) }
    $secureToken.Dispose()
}
