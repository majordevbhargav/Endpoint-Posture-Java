<#
.SYNOPSIS  D7: create the dedicated service account and grant "Log on as a service".
.NOTES     Run as Administrator. This only creates the LOCAL account on the posture host.
           You must separately grant it, on the TARGET endpoints, only the rights the agents
           need (WMI/CIM read, WinRM access via "Remote Management Users" / "Performance Monitor
           Users" / "Event Log Readers"). Do NOT make it a domain admin. Review before using.
#>
param([string]$Name = "svc-posture", [string]$InstallRoot = "C:\PostureEngine")
$ErrorActionPreference = "Stop"
$pwd = Read-Host "Password for $Name (long, random, store in your vault)" -AsSecureString
if (-not (Get-LocalUser -Name $Name -ErrorAction SilentlyContinue)) {
    New-LocalUser -Name $Name -Password $pwd -PasswordNeverExpires -UserMayNotChangePassword `
        -Description "VE Posture Engine service account" | Out-Null
}
# Grant SeServiceLogonRight
$sid = (Get-LocalUser $Name).SID.Value
$inf = Join-Path $env:TEMP "svc.inf"; $db = Join-Path $env:TEMP "svc.sdb"
secedit /export /cfg $inf | Out-Null
$cfg = Get-Content $inf
$line = $cfg | Where-Object { $_ -like "SeServiceLogonRight*" }
$cfg = $cfg -replace [regex]::Escape($line), "$line,*$sid"
Set-Content $inf $cfg
secedit /configure /db $db /cfg $inf /areas USER_RIGHTS | Out-Null
Remove-Item $inf, $db -ErrorAction SilentlyContinue
# NTFS: modify on install root only
New-Item -ItemType Directory -Force -Path $InstallRoot | Out-Null
icacls $InstallRoot /grant "${Name}:(OI)(CI)M" | Out-Null
Write-Host "Account ready. NEXT: log on once as $Name (runas) and run scripts\Save-PostureCredential.ps1" -ForegroundColor Green
Write-Host "(DPAPI file only decrypts for this account on this machine.)"
