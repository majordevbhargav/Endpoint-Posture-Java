<# Starts the provisioned worker service only when protected credentials are present. #>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$DataDirectory = "$env:ProgramData\EndpointPostureWorker",
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_-]{2,31}$')][string]$ServiceAccount = 'svc-posture-worker',
    [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
$expectedIdentity = "$env:COMPUTERNAME\$ServiceAccount"
$acceptedServiceIdentities = @($expectedIdentity, ".\$ServiceAccount")
$service = Get-CimInstance Win32_Service -Filter "Name='EndpointPostureWorker'"
if (-not $service) { throw 'EndpointPostureWorker is not installed.' }
if ($service.StartName -notin $acceptedServiceIdentities) { throw "Service identity mismatch: expected $expectedIdentity; found $($service.StartName)." }
$stateFile = Join-Path ([IO.Path]::GetFullPath($DataDirectory)) 'state.json'
if (-not (Test-Path -LiteralPath $stateFile)) { throw "DPAPI credential state is not initialized: $stateFile" }
$state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
if ($state.format -ne 1 -or [string]::IsNullOrWhiteSpace($state.protectedApiKey)) { throw 'Credential state does not contain a protected worker key.' }
if ($TimeoutSeconds -lt 5 -or $TimeoutSeconds -gt 300) { throw 'TimeoutSeconds must be from 5 through 300.' }
if (-not $PSCmdlet.ShouldProcess('EndpointPostureWorker',"Start as $($service.StartName)")) { return }
if ($service.State -ne 'Running') { Start-Service -Name EndpointPostureWorker }
(Get-Service -Name EndpointPostureWorker).WaitForStatus('Running',[TimeSpan]::FromSeconds($TimeoutSeconds))
$service = Get-CimInstance Win32_Service -Filter "Name='EndpointPostureWorker'"
if (-not $service.ProcessId -or -not (Get-Process -Id $service.ProcessId -ErrorAction SilentlyContinue)) {
    throw 'SCM reported Running without a live worker process.'
}
Write-Host "EndpointPostureWorker is Running as $($service.StartName); process ID $($service.ProcessId); worker ID $($state.workerId)."
