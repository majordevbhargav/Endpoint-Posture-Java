<#
.SYNOPSIS
Installs the staged worker wrapper and configures the dedicated local service identity.
.DESCRIPTION
This script never creates an account, changes ACLs, starts the service or overwrites an
existing service. It requires an already provisioned account and prior ACL provisioning.
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$InstallDirectory = "$env:ProgramFiles\EndpointPostureWorker",
    [string]$DataDirectory = "$env:ProgramData\EndpointPostureWorker",
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_-]{2,31}$')][string]$ServiceAccount = 'svc-posture-worker',
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ExpectedWinSWSha256
)

$ErrorActionPreference = 'Stop'
$principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Run service installation from an elevated PowerShell session.' }
if ($ServiceAccount -eq 'svc-posture') { throw 'The worker must use its dedicated svc-posture-worker account, separate from backend svc-posture.' }
$accountName = "$env:COMPUTERNAME\$ServiceAccount"
$acceptedServiceIdentities = @($accountName, ".\$ServiceAccount")
if (-not (Get-LocalUser -Name $ServiceAccount -ErrorAction SilentlyContinue)) { throw "Required local account is not provisioned: $accountName" }

$InstallDirectory = [IO.Path]::GetFullPath($InstallDirectory)
$wrapper = Join-Path $InstallDirectory 'EndpointPostureWorker.exe'
$config = Join-Path $InstallDirectory 'EndpointPostureWorker.xml'
$workerJar = Join-Path $InstallDirectory 'worker-runtime.jar'
$DataDirectory = [IO.Path]::GetFullPath($DataDirectory)
$stateFile = Join-Path $DataDirectory 'state.json'
$dataDirectory = $DataDirectory
$logDirectory = Join-Path $dataDirectory 'logs'
foreach ($path in @($wrapper,$config,$workerJar,(Join-Path $InstallDirectory 'scripts'),$dataDirectory,$logDirectory)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Required staged/ACL-provisioned path is missing: $path" }
}
if (-not (Test-Path -LiteralPath $stateFile)) { throw "Worker credentials must be initialized under $ServiceAccount first: $stateFile" }
$savedState = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
if ($savedState.format -ne 1 -or [string]::IsNullOrWhiteSpace($savedState.protectedApiKey)) { throw 'Worker state file is not a valid DPAPI-protected credential record.' }
$xmlCheck = [Xml.XmlDocument]::new(); $xmlCheck.Load($config)
if ([IO.Path]::GetFullPath($xmlCheck.SelectSingleNode('/service/env[@name="EP_WORKER_DATA_DIRECTORY"]').GetAttribute('value')) -ne $dataDirectory) {
    throw 'The data path in the service XML differs from DataDirectory. Re-stage and provision matching paths.'
}
if (Get-Service -Name EndpointPostureWorker -ErrorAction SilentlyContinue) {
    throw 'EndpointPostureWorker is already installed. Inspect it; this installer never overwrites an existing service.'
}
$productVersion = [Diagnostics.FileVersionInfo]::GetVersionInfo($wrapper).ProductVersion
if ($productVersion -notmatch '^v?2\.12\.0(?:\.0)?(?:\+.*)?$') { throw "Expected WinSW v2.12.0; found '$productVersion'." }
$actualHash = (Get-FileHash -LiteralPath $wrapper -Algorithm SHA256).Hash
if ($actualHash -ne $ExpectedWinSWSha256.ToUpperInvariant()) { throw 'Staged WinSW SHA-256 differs from the approved digest.' }

$accountSid = ("$accountName" -as [Security.Principal.NTAccount]).Translate([Security.Principal.SecurityIdentifier])
function Assert-WorkerAcl([string]$Path,[bool]$NeedsModify) {
    $acl = Get-Acl -LiteralPath $Path
    $found = $false
    foreach ($rule in $acl.Access) {
        try { $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]) } catch { continue }
        $required = if ($NeedsModify) { [Security.AccessControl.FileSystemRights]::Modify } else { [Security.AccessControl.FileSystemRights]::ReadAndExecute }
        if ($sid -eq $accountSid -and $rule.AccessControlType -eq 'Allow' -and
            ($rule.FileSystemRights -band $required) -eq $required) { $found = $true }
    }
    if (-not $found) { throw "Required service account ACL is missing on $Path. Run Configure-WorkerServiceAcl.ps1 explicitly and verify it." }
}
Assert-WorkerAcl $InstallDirectory $false
Assert-WorkerAcl $dataDirectory $true
Assert-WorkerAcl $logDirectory $true

if (-not $PSCmdlet.ShouldProcess('EndpointPostureWorker', "Install wrapper and assign service identity $accountName")) { return }
$credential = Get-Credential -UserName $accountName -Message 'Enter the dedicated worker service account password. It is passed through the PowerShell service API, not the command line.'
$installed = $false
try {
    Push-Location $InstallDirectory
    try {
        & $wrapper install
        if ($LASTEXITCODE -ne 0) { throw "WinSW install failed with exit code $LASTEXITCODE." }
    } finally { Pop-Location }
    $installed = $true

    Set-Service -Name EndpointPostureWorker -Credential $credential
    $service = Get-CimInstance Win32_Service -Filter "Name='EndpointPostureWorker'"
    if (-not $service -or $service.StartName -notin $acceptedServiceIdentities) {
        throw 'SCM did not report the requested worker service identity after configuration.'
    }
    Set-Service -Name EndpointPostureWorker -StartupType Automatic
    if ((Get-Service -Name EndpointPostureWorker).Status -ne 'Stopped') { throw 'Worker service unexpectedly started during installation.' }
    Write-Host "Installed EndpointPostureWorker as $($service.StartName), StartupType=Automatic, Status=Stopped. Credential state was initialized under that account before installation."
} catch {
    if ($installed -and (Get-Service -Name EndpointPostureWorker -ErrorAction SilentlyContinue)) {
        try { Push-Location $InstallDirectory; & $wrapper uninstall; Pop-Location } catch { Write-Warning 'Automatic cleanup did not complete; service is configured for Manual startup. Inspect it before retrying.' }
    }
    throw
} finally { if ($credential) { $credential.Password.Dispose() } }
