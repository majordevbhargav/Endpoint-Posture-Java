<# Stops the worker through SCM and verifies both SCM and Java process termination. #>
[CmdletBinding(SupportsShouldProcess = $true)]
param([int]$TimeoutSeconds = 60)

$ErrorActionPreference = 'Stop'
if ($TimeoutSeconds -lt 15 -or $TimeoutSeconds -gt 300) { throw 'TimeoutSeconds must be from 15 through 300.' }
$service = Get-CimInstance Win32_Service -Filter "Name='EndpointPostureWorker'"
if (-not $service) { throw 'EndpointPostureWorker is not installed.' }
if (-not $PSCmdlet.ShouldProcess('EndpointPostureWorker','Stop worker service through Windows SCM')) { return }
if ($service.State -ne 'Stopped') {
    Stop-Service -Name EndpointPostureWorker
    (Get-Service -Name EndpointPostureWorker).WaitForStatus('Stopped',[TimeSpan]::FromSeconds($TimeoutSeconds))
}
$service = Get-CimInstance Win32_Service -Filter "Name='EndpointPostureWorker'"
if ($service.ProcessId -and (Get-Process -Id $service.ProcessId -ErrorAction SilentlyContinue)) {
    throw "SCM reports stopped but worker process $($service.ProcessId) remains alive. Preserve the database and investigate; do not force-kill it."
}
Write-Host 'EndpointPostureWorker is Stopped and the service process has exited. Persistent state was retained.'
