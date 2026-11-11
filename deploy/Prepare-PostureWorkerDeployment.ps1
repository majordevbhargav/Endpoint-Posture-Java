<#
.SYNOPSIS
Stages a new worker installation without installing or changing a Windows service.
.DESCRIPTION
Requires the shaded worker JAR and an organization-approved WinSW 2.12.0 binary. The
expected SHA-256 is mandatory; this script does not download or bundle WinSW.
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [Parameter(Mandatory = $true)][string]$BackendUrl,
    [string]$InstallDirectory = "$env:ProgramFiles\EndpointPostureWorker",
    [string]$DataDirectory = "$env:ProgramData\EndpointPostureWorker",
    [Parameter(Mandatory = $true)][string]$WinSWPath,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ExpectedWinSWSha256
)

$ErrorActionPreference = 'Stop'
$RepositoryRoot = [IO.Path]::GetFullPath($RepositoryRoot)
$InstallDirectory = [IO.Path]::GetFullPath($InstallDirectory)
$DataDirectory = [IO.Path]::GetFullPath($DataDirectory)
$LogDirectory = Join-Path $DataDirectory 'logs'
$StateFile = Join-Path $DataDirectory 'state.json'
$WinSWPath = [IO.Path]::GetFullPath($WinSWPath)

$uri = [Uri]$BackendUrl
if (-not $uri.IsAbsoluteUri -or $uri.Scheme -notin @('http','https') -or
    -not [string]::IsNullOrEmpty($uri.UserInfo) -or $uri.Query -or $uri.Fragment -or
    ($uri.AbsolutePath -notin @('', '/')) -or
    ($uri.Scheme -eq 'http' -and $uri.Host -notin @('localhost','127.0.0.1','::1'))) {
    throw 'BackendUrl must be an origin only; use HTTPS except for a local loopback test backend. Do not include credentials, path, query or fragment.'
}
if (Test-Path -LiteralPath $InstallDirectory) { throw "Install directory already exists; refusing to overwrite: $InstallDirectory" }
if (Test-Path -LiteralPath $DataDirectory) { throw "Data directory already exists; inspect and preserve it before staging a new worker: $DataDirectory" }

$workerJar = Join-Path $RepositoryRoot 'worker-runtime\target\worker-runtime-0.1.0-SNAPSHOT.jar'
$scriptsSource = Join-Path $RepositoryRoot 'scripts'
$xmlTemplate = Join-Path $PSScriptRoot 'EndpointPostureWorker.xml'
 $credentialBootstrap = Join-Path $PSScriptRoot 'Initialize-PostureWorkerCredentials.ps1'
foreach ($required in @($workerJar,$scriptsSource,$xmlTemplate,$credentialBootstrap,$WinSWPath)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Required deployment input is missing: $required" }
}

$fileInfo = [Diagnostics.FileVersionInfo]::GetVersionInfo($WinSWPath)
$productVersion = $fileInfo.ProductVersion
if ($productVersion -notmatch '^v?2\.12\.0(?:\.0)?(?:\+.*)?$') {
    throw "Expected approved WinSW v2.12.0 metadata; found '$productVersion'. No files were staged."
}
$actualHash = (Get-FileHash -LiteralPath $WinSWPath -Algorithm SHA256).Hash
if ($actualHash -ne $ExpectedWinSWSha256.ToUpperInvariant()) {
    throw 'WinSW SHA-256 does not match the independently approved digest. No files were staged.'
}

if (-not $PSCmdlet.ShouldProcess($InstallDirectory, 'Stage worker JAR, scripts, WinSW 2.12.0 and service XML')) { return }
$java = Get-Command java.exe -ErrorAction Stop
$javaPath = [IO.Path]::GetFullPath($java.Source)
New-Item -ItemType Directory -Path $InstallDirectory -ErrorAction Stop | Out-Null
New-Item -ItemType Directory -Path $DataDirectory | Out-Null
New-Item -ItemType Directory -Path $LogDirectory -Force | Out-Null
Copy-Item -LiteralPath $workerJar -Destination (Join-Path $InstallDirectory 'worker-runtime.jar')
Copy-Item -LiteralPath $scriptsSource -Destination (Join-Path $InstallDirectory 'scripts') -Recurse
Copy-Item -LiteralPath $credentialBootstrap -Destination (Join-Path $InstallDirectory 'Initialize-PostureWorkerCredentials.ps1')
Copy-Item -LiteralPath $WinSWPath -Destination (Join-Path $InstallDirectory 'EndpointPostureWorker.exe')

$xml = [Xml.XmlDocument]::new()
$xml.PreserveWhitespace = $true
$xml.Load($xmlTemplate)
$xml.SelectSingleNode('/service/executable').InnerText = $javaPath
$xml.SelectSingleNode('/service/env[@name="EP_BACKEND_URL"]').SetAttribute('value',$uri.GetLeftPart([UriPartial]::Authority))
$xml.SelectSingleNode('/service/env[@name="EP_WORKER_STATE_FILE"]').SetAttribute('value',$StateFile)
$xml.SelectSingleNode('/service/env[@name="EP_WORKER_DATA_DIRECTORY"]').SetAttribute('value',$DataDirectory)
$xml.SelectSingleNode('/service/logpath').InnerText = $LogDirectory
$xml.Save((Join-Path $InstallDirectory 'EndpointPostureWorker.xml'))

Write-Host "Staged worker package at $InstallDirectory. WinSW ProductVersion=$productVersion; SHA256=$actualHash."
Write-Host "Next: provision $env:COMPUTERNAME\svc-posture-worker, run Configure-WorkerServiceAcl.ps1 for the staged/data/log paths, initialize credentials as that account, then run Install-PostureWorkerService.ps1."
