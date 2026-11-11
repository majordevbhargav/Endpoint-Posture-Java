<#
.SYNOPSIS
Creates the dedicated local worker account and grants the service logon right.
.DESCRIPTION
Run only by an administrator after reviewing the deployment guide. The password is
requested as a SecureString and is never written to a file, command line or output.
This script does not install or start a service and does not alter an existing account.
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param([ValidatePattern('^[A-Za-z][A-Za-z0-9_-]{2,31}$')][string]$AccountName = 'svc-posture-worker')

$ErrorActionPreference = 'Stop'
$principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this account-provisioning step from an elevated PowerShell session.'
}
if (Get-LocalUser -Name $AccountName -ErrorAction SilentlyContinue) {
    throw "Local account '$AccountName' already exists. Inspect it; this script will not change it."
}

if (-not $PSCmdlet.ShouldProcess("$env:COMPUTERNAME\$AccountName", 'Create local worker service account')) { return }
$securePassword = Read-Host "Enter a new password for $env:COMPUTERNAME\$AccountName" -AsSecureString
if ($securePassword.Length -lt 20) { $securePassword.Dispose(); throw 'Use a randomly generated service password of at least 20 characters.' }

$temporaryBase = Join-Path $env:TEMP ("worker-service-right-" + [Guid]::NewGuid().ToString('N'))
$exportPath = "$temporaryBase.inf"
$databasePath = "$temporaryBase.sdb"
try {
    New-LocalUser -Name $AccountName -Password $securePassword `
        -Description 'Dedicated Endpoint Posture Windows worker service identity' | Out-Null
    $sid = (Get-LocalUser -Name $AccountName).SID.Value

    & secedit.exe /export /cfg $exportPath /areas USER_RIGHTS | Out-Null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $exportPath)) {
        throw 'Could not export local user-rights policy. Grant Log on as a service through Local Security Policy or domain policy.'
    }
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.AddRange([string[]](Get-Content -LiteralPath $exportPath))
    $index = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^SeServiceLogonRight\s*=') { $index = $i; break }
    }
    if ($index -lt 0) { throw 'SeServiceLogonRight was absent from the exported policy; grant it manually in Local Security Policy/GPO.' }
    if ($lines[$index] -notmatch [regex]::Escape("*$sid")) {
        $lines[$index] = $lines[$index].TrimEnd() + ",*$sid"
        Set-Content -LiteralPath $exportPath -Value $lines -Encoding Unicode
        & secedit.exe /configure /db $databasePath /cfg $exportPath /areas USER_RIGHTS | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Local account was created, but the service logon right could not be applied. Follow the manual policy instructions.' }
    }
    Write-Host "Created $env:COMPUTERNAME\$AccountName. Review local/domain policy and verify 'Log on as a service' before installation."
    Write-Host 'Password expiry/rotation remains governed by the machine organization policy; update the SCM credential during planned rotation.'
} finally {
    $securePassword.Dispose()
    Remove-Item -LiteralPath $exportPath, $databasePath -Force -ErrorAction SilentlyContinue
}
