<# Removes only the worker SCM registration. Persistent credentials, SQLite outbox and logs are retained. #>
[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [string]$InstallDirectory = "$env:ProgramFiles\EndpointPostureWorker",
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ExpectedWinSWSha256
)

$ErrorActionPreference = 'Stop'
$principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Run service removal from an elevated PowerShell session.' }
$wrapper = Join-Path ([IO.Path]::GetFullPath($InstallDirectory)) 'EndpointPostureWorker.exe'
if (-not (Test-Path -LiteralPath $wrapper)) { throw "Worker WinSW executable not found: $wrapper" }
$productVersion = [Diagnostics.FileVersionInfo]::GetVersionInfo($wrapper).ProductVersion
if ($productVersion -notmatch '^v?2\.12\.0(?:\.0)?(?:\+.*)?$') { throw "Expected WinSW v2.12.0; found '$productVersion'." }
if ((Get-FileHash -LiteralPath $wrapper -Algorithm SHA256).Hash -ne $ExpectedWinSWSha256.ToUpperInvariant()) { throw 'WinSW SHA-256 differs from the approved digest.' }
$service = Get-Service -Name EndpointPostureWorker -ErrorAction SilentlyContinue
if (-not $service) { throw 'EndpointPostureWorker is not installed; no changes made.' }
if (-not $PSCmdlet.ShouldProcess('EndpointPostureWorker', 'Stop and unregister worker service; retain data and logs')) { return }
if ($service.Status -ne 'Stopped') {
    Stop-Service -Name EndpointPostureWorker
    (Get-Service -Name EndpointPostureWorker).WaitForStatus('Stopped',[TimeSpan]::FromSeconds(60))
}
$wmi = Get-CimInstance Win32_Service -Filter "Name='EndpointPostureWorker'"
if ($wmi.ProcessId -and (Get-Process -Id $wmi.ProcessId -ErrorAction SilentlyContinue)) { throw "Worker process $($wmi.ProcessId) is still alive; service registration retained." }
Push-Location (Split-Path -Parent $wrapper)
try {
    & $wrapper uninstall
    if ($LASTEXITCODE -ne 0) { throw "WinSW uninstall failed with exit code $LASTEXITCODE." }
} finally { Pop-Location }
Write-Host 'Worker service removed. Credential state, outbox database/WAL files and logs were retained.'
