<#
.SYNOPSIS
    Endpoint 360 security-indicator sampler (E2). Samples established TCP
    connections ON the endpoint several times and submits the raw snapshots.
    Detection happens in the backend (SecurityIndicatorAnalyzer), not here.

.NOTES
    Same contract as diagnostic_agent.ps1: WinRM for remote targets, a
    WINRM_UNAVAILABLE report when the endpoint cannot be reached, one
    RESULT_JSON line on stdout, key from $env:POSTURE_API_KEY.
    WinRmOperationTimeoutSec must exceed (SampleCount-1)*SampleIntervalSec.
#>
param(
    [string]$PostureAppBase = "http://localhost:8090",
    [string]$ComputerName = $env:COMPUTERNAME,
    [string]$Mac,
    [string]$JobId,
    [int]$WinRmOpenTimeoutSec = 20,
    [int]$WinRmOperationTimeoutSec = 100,
    [int]$SubmitTimeoutSec = 20,
    [int]$SampleCount = 12,
    [int]$SampleIntervalSec = 5,
    [int]$MaxConnectionsPerSample = 300,
    [string]$Username,
    [securestring]$Password,
    [string]$PlainPassword,
    [string]$CommonCredPath = "$PSScriptRoot\posture_common_cred.xml"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$LocalIPs = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Select-Object -ExpandProperty IPAddress)
$IsRemote = ($ComputerName -ne $env:COMPUTERNAME) -and ($ComputerName -notin $LocalIPs) -and ($ComputerName -ne '127.0.0.1') -and ($ComputerName -ne 'localhost')

function Get-SecurityCred {
    $HasExplicitOverride = [bool]($Username -or $Password -or $PlainPassword)
    if (-not $HasExplicitOverride -and (Test-Path $CommonCredPath)) {
        try {
            $Stored = Import-Clixml -Path $CommonCredPath
            $StoredUser = $Stored.UserName
            $BareUser = $StoredUser; $Prefix = $null
            if ($StoredUser -match '\\') { $Parts = $StoredUser -split '\\', 2; $Prefix = $Parts[0]; $BareUser = $Parts[1] }
            $PrefixIsIp = $Prefix -and ($Prefix -match '^\d{1,3}(\.\d{1,3}){3}$')
            $NeedsRequalify = (-not $Prefix) -or ($Prefix -eq '.') -or ($Prefix -ieq $env:COMPUTERNAME) -or ($PrefixIsIp -and $Prefix -ne $ComputerName)
            if ($NeedsRequalify) {
                $Stored = New-Object System.Management.Automation.PSCredential("$ComputerName\$BareUser", $Stored.Password)
            }
            Write-Host "Using stored common credential ($($Stored.UserName)) for $ComputerName" -ForegroundColor DarkGray
            return $Stored
        }
        catch {
            if ($_.Exception.Message -match 'Key not valid|invalid in the current context|padding is invalid|Cryptographic') {
                throw "Could not decrypt the stored credential at $CommonCredPath. Re-run Save-PostureCredential.ps1 under the same Windows account that runs this script."
            }
            throw "Could not load stored credential from $CommonCredPath : $($_.Exception.Message)"
        }
    }
    if (-not $Username) { $Username = Read-Host "Username on $ComputerName" }
    if ($PlainPassword) {
        $Sec = New-Object System.Security.SecureString
        foreach ($ch in $PlainPassword.ToCharArray()) { $Sec.AppendChar($ch) }
        $Sec.MakeReadOnly()
    }
    elseif ($Password) { $Sec = $Password }
    else { $Sec = Read-Host "Password for $Username" -AsSecureString }
    $Q = if ($Username -match '\\') { $Username } else { "$ComputerName\$Username" }
    return New-Object System.Management.Automation.PSCredential($Q, $Sec)
}

function Submit-FailureAndExit {
    param([string]$Detail)
    Write-Host "ERROR: $Detail" -ForegroundColor Red
    $Fail = [ordered]@{ jobId = $JobId; computer = $ComputerName; status = "ERROR"; detail = $Detail; submitted = $false }
    Write-Output ("RESULT_JSON:" + ($Fail | ConvertTo-Json -Compress))
    exit 1
}

if (-not $Mac) {
    if ($IsRemote) { Submit-FailureAndExit "No -Mac was supplied for remote target $ComputerName." }
    $Nic = Get-CimInstance -ClassName Win32_NetworkAdapterConfiguration -Filter "IPEnabled=True" -ErrorAction SilentlyContinue | Select-Object -First 1
    $Mac = $Nic.MACAddress
    if (-not $Mac) { Submit-FailureAndExit "Could not determine this machine's MAC address." }
}

# Runs ON the endpoint. Self-contained, Windows PowerShell 5.1 compatible.
$SampleBlock = {
    param([int]$Count, [int]$IntervalSec, [int]$MaxPerSample)
    $ErrorActionPreference = "SilentlyContinue"
    $names = @{}
    $samples = @()
    $start = Get-Date
    for ($i = 0; $i -lt $Count; $i++) {
        $tick = Get-Date
        $rows = @(Get-NetTCPConnection -State Established |
            Where-Object { $_.RemoteAddress -notin @('127.0.0.1', '::1', '0.0.0.0', '::') } |
            Select-Object -Property LocalPort, RemoteAddress, RemotePort, OwningProcess -First $MaxPerSample)
        $list = @()
        foreach ($c in $rows) {
            $procId = [int]$c.OwningProcess
            if (-not $names.ContainsKey($procId)) {
                $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
                $names[$procId] = if ($p) { $p.ProcessName } else { "unknown" }
            }
            $list += [ordered]@{
                remoteAddress = [string]$c.RemoteAddress
                remotePort    = [int]$c.RemotePort
                localPort     = [int]$c.LocalPort
                pid           = $procId
                process       = $names[$procId]
            }
        }
        $samples += [ordered]@{
            index       = $i
            offsetSec   = [math]::Round(((Get-Date) - $start).TotalSeconds, 1)
            connections = @($list)
        }
        if ($i -lt $Count - 1) {
            $sleepMs = [int](($IntervalSec - ((Get-Date) - $tick).TotalSeconds) * 1000)
            if ($sleepMs -gt 0) { Start-Sleep -Milliseconds $sleepMs }
        }
    }
    [ordered]@{ hostname = $env:COMPUTERNAME; samples = @($samples) }
}

$Result = $null
$Status = "OK"
$Detail = $null

if ($IsRemote) {
    try { $Cred = Get-SecurityCred } catch { Submit-FailureAndExit $_.Exception.Message }
    try {
        $Opt = New-PSSessionOption -OpenTimeout ($WinRmOpenTimeoutSec * 1000) -OperationTimeout ($WinRmOperationTimeoutSec * 1000)
        $Result = Invoke-Command -ComputerName $ComputerName -Credential $Cred -SessionOption $Opt `
            -ScriptBlock $SampleBlock -ArgumentList $SampleCount, $SampleIntervalSec, $MaxConnectionsPerSample -ErrorAction Stop
    }
    catch {
        $Status = "WINRM_UNAVAILABLE"
        $Detail = "Could not sample connections on $ComputerName over WinRM: $($_.Exception.Message)"
        Write-Host "WARNING: $Detail" -ForegroundColor Yellow
    }
}
else {
    try { $Result = & $SampleBlock $SampleCount $SampleIntervalSec $MaxConnectionsPerSample }
    catch { Submit-FailureAndExit "Local sampling failed: $($_.Exception.Message)" }
}

if ($Status -eq "OK" -and -not $Result) { Submit-FailureAndExit "Sampling returned no data from $ComputerName." }

$ReportHostname = if ($Result -and $Result.hostname) { [string]$Result.hostname } elseif (-not $IsRemote) { $env:COMPUTERNAME } else { $null }
$ReportIp = if ($ComputerName -match '^\d{1,3}(\.\d{1,3}){3}$') { $ComputerName } else { $null }
$SampleList = if ($Result) { @($Result.samples) } else { @() }

$Payload = [ordered]@{
    jobId           = $JobId
    endpoint        = [ordered]@{ mac = $Mac; hostname = $ReportHostname; ip = $ReportIp }
    status          = $Status
    detail          = $Detail
    windowSeconds   = [int](($SampleCount - 1) * $SampleIntervalSec)
    intervalSeconds = $SampleIntervalSec
    samples         = $SampleList
} | ConvertTo-Json -Depth 10

$Headers = @{}
if (-not [string]::IsNullOrWhiteSpace($env:POSTURE_API_KEY)) { $Headers["X-Posture-Api-Key"] = $env:POSTURE_API_KEY.Trim() }
else { Write-Host "WARNING: POSTURE_API_KEY is not set - the submit will be rejected." -ForegroundColor Yellow }

try {
    $Resp = Invoke-RestMethod -Uri "$PostureAppBase/api/v1/security-indicators" -Method Post -Body $Payload `
        -ContentType "application/json" -Headers $Headers -TimeoutSec $SubmitTimeoutSec
    Write-Host "Submitted security indicators for $ComputerName ($Mac): status=$($Resp.status) risk=$($Resp.riskLevel)" -ForegroundColor Green
    $Out = [ordered]@{ jobId = $JobId; computer = $ComputerName; mac = $Mac; status = $Resp.status
                       detail = "risk=$($Resp.riskLevel)"; submitted = $true }
    Write-Output ("RESULT_JSON:" + ($Out | ConvertTo-Json -Compress))
}
catch {
    $Out = [ordered]@{ jobId = $JobId; computer = $ComputerName; mac = $Mac; status = "ERROR"
                       detail = "Sampled OK but could not submit: $($_.Exception.Message)"
                       submitted = $false; submitError = $_.Exception.Message }
    Write-Host "ERROR submitting security indicators: $($_.Exception.Message)" -ForegroundColor Red
    Write-Output ("RESULT_JSON:" + ($Out | ConvertTo-Json -Compress))
    exit 1
}