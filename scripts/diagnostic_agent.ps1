<#
.SYNOPSIS
    Endpoint 360 diagnostic agent: gateway ping, DNS, TCP 443 and traceroute,
    run ON the endpoint so the numbers show what the user's machine sees.

.DESCRIPTION
    JobWorker launches this once per DIAGNOSTIC_CHECK job. For a remote target
    the probes run inside Invoke-Command (WinRM) on that endpoint. If WinRM
    cannot be reached, a report with status WINRM_UNAVAILABLE is still
    submitted: that is a distinct, non-failing result, not a job failure.

    The report is POSTed to /api/v1/diagnostics and one RESULT_JSON line is
    always printed so JobWorker has a machine-readable outcome.

.NOTES
    AUTHENTICATION: sends POSTURE_API_KEY as the X-Posture-Api-Key header.
        $env:POSTURE_API_KEY = "<value of app.posture.api-key>"
        .\diagnostic_agent.ps1

    REMOTE REQUIREMENTS: WinRM enabled on the target (Enable-PSRemoting) and
    reachable from this machine. Connecting by IP address to a machine that is
    not in your domain also needs it listed in this machine's WinRM
    TrustedHosts, for example:
        Set-Item WSMan:\localhost\Client\TrustedHosts -Value "10.66.1.*" -Force
    When that is missing, the report says so in its detail text.

    -Mac is passed by JobWorker so a WINRM_UNAVAILABLE report can still be
    attached to the right device. A local run derives it automatically.
#>

param(
    [string]$PostureAppBase = "http://localhost:8090",
    [string]$ComputerName = $env:COMPUTERNAME,
    [string]$Mac,
    [string]$JobId,
    [int]$WinRmOpenTimeoutSec = 20,
    [int]$WinRmOperationTimeoutSec = 70,
    [int]$SubmitTimeoutSec = 20,
    [string]$DnsTestName = "www.microsoft.com",
    [string]$InternetTarget = "8.8.8.8",
    [string]$Username,
    [securestring]$Password,
    [string]$PlainPassword,
    [string]$CommonCredPath = "$PSScriptRoot\posture_common_cred.xml"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

# Same local-vs-remote rule as the other agents: an IP that belongs to this
# machine is a local run, never a remote one.
$LocalIPs = @(
    Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty IPAddress
)
$IsRemote = ($ComputerName -ne $env:COMPUTERNAME) -and ($ComputerName -notin $LocalIPs) -and ($ComputerName -ne '127.0.0.1') -and ($ComputerName -ne 'localhost')
$Cred = $null

function Get-DiagnosticCred {
    $HasExplicitOverride = [bool]($Username -or $Password -or $PlainPassword)

    if (-not $HasExplicitOverride -and (Test-Path $CommonCredPath)) {
        try {
            $Stored = Import-Clixml -Path $CommonCredPath
            $StoredUser = $Stored.UserName

            $BareUser = $StoredUser
            $Prefix = $null

            if ($StoredUser -match '\\') {
                $Parts = $StoredUser -split '\\', 2
                $Prefix = $Parts[0]
                $BareUser = $Parts[1]
            }

            $PrefixIsIp = $Prefix -and ($Prefix -match '^\d{1,3}(\.\d{1,3}){3}$')

            $NeedsRequalify =
                (-not $Prefix) -or
                ($Prefix -eq '.') -or
                ($Prefix -ieq $env:COMPUTERNAME) -or
                ($PrefixIsIp -and $Prefix -ne $ComputerName)

            if ($NeedsRequalify) {
                $QualifiedStoredUser = "$ComputerName\$BareUser"
                $Stored = New-Object System.Management.Automation.PSCredential($QualifiedStoredUser, $Stored.Password)
            }

            Write-Host "Using stored common credential ($($Stored.UserName)) for $ComputerName" -ForegroundColor DarkGray
            return $Stored
        }
        catch {
            $ImportErr = $_.Exception.Message
            if ($ImportErr -match 'Key not valid|invalid in the current context|padding is invalid|Cryptographic') {
                throw "Could not decrypt the stored credential at $CommonCredPath. This almost always means it was saved under a different Windows user account/session. Re-run Save-PostureCredential.ps1 under the same account/session that runs this script."
            }
            throw "Could not load stored credential from $CommonCredPath : $ImportErr"
        }
    }

    if (-not $Username) {
        $Username = Read-Host "Username on $ComputerName (e.g. Administrator)"
    }

    if ($PlainPassword) {
        $SecurePwd = New-Object System.Security.SecureString
        foreach ($ch in $PlainPassword.ToCharArray()) { $SecurePwd.AppendChar($ch) }
        $SecurePwd.MakeReadOnly()
    }
    elseif ($Password) {
        $SecurePwd = $Password
    }
    else {
        $SecurePwd = Read-Host "Password for $Username" -AsSecureString
    }

    $QualifiedUser = if ($Username -match '\\') { $Username } else { "$ComputerName\$Username" }
    return New-Object System.Management.Automation.PSCredential($QualifiedUser, $SecurePwd)
}

function Submit-FailureAndExit {
    param([string]$Detail)

    Write-Host ""
    Write-Host "ERROR: $Detail" -ForegroundColor Red

    $FailResult = [ordered]@{
        jobId     = $JobId
        computer  = $ComputerName
        status    = "ERROR"
        detail    = $Detail
        submitted = $false
    }

    Write-Output ("RESULT_JSON:" + ($FailResult | ConvertTo-Json -Compress))
    exit 1
}

# ---------------------------------------------------------------------------
# MAC: needed to attach the report to a device, even if WinRM never connects.
# ---------------------------------------------------------------------------
if (-not $Mac) {
    if ($IsRemote) {
        Submit-FailureAndExit "No -Mac was supplied for remote target $ComputerName, so a report cannot be attached to a device."
    }
    $Nic = Get-CimInstance -ClassName Win32_NetworkAdapterConfiguration -Filter "IPEnabled=True" -ErrorAction SilentlyContinue |
        Select-Object -First 1
    $Mac = $Nic.MACAddress
    if (-not $Mac) {
        Submit-FailureAndExit "Could not determine this machine's MAC address (no IP-enabled network adapter found)."
    }
}

# ---------------------------------------------------------------------------
# The probes. This block runs ON the endpoint (locally, or inside Invoke-Command),
# so it must be self-contained and work on Windows PowerShell 5.1.
# ---------------------------------------------------------------------------
$ProbeBlock = {
    param([string]$DnsName, [string]$InternetTarget)
    $ErrorActionPreference = "SilentlyContinue"

    function Test-Ping {
        param([string]$Target, [int]$Count)
        $result = [ordered]@{ address = $Target; reachable = $false; avgMs = $null; lossPct = 100 }
        if (-not $Target) { return $result }
        $ping = New-Object System.Net.NetworkInformation.Ping
        $times = @()
        for ($i = 0; $i -lt $Count; $i++) {
            try {
                $r = $ping.Send($Target, 1000)
                if ($r.Status -eq "Success") { $times += [double]$r.RoundtripTime }
            } catch { }
        }
        $ping.Dispose()
        if ($times.Count -gt 0) {
            $result.reachable = $true
            $result.avgMs = [math]::Round(($times | Measure-Object -Average).Average, 1)
        }
        $result.lossPct = [math]::Round((($Count - $times.Count) / $Count) * 100, 0)
        return $result
    }

    # 1. Default gateway
    $gwAddr = (Get-NetRoute -DestinationPrefix "0.0.0.0/0" |
        Where-Object { $_.NextHop -and $_.NextHop -ne "0.0.0.0" } |
        Sort-Object { $_.RouteMetric + $_.InterfaceMetric } |
        Select-Object -First 1).NextHop
    $gateway = Test-Ping $gwAddr 4

    # 2. DNS
    $dns = [ordered]@{ target = $DnsName; resolved = $false; ms = $null; error = $null }
    try {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $answer = Resolve-DnsName -Name $DnsName -Type A -DnsOnly -QuickTimeout -ErrorAction Stop
        $sw.Stop()
        $dns.ms = [math]::Round($sw.Elapsed.TotalMilliseconds, 0)
        $dns.resolved = [bool]($answer | Where-Object { $_.IPAddress })
    } catch {
        $dns.error = $_.Exception.Message
    }

    # 3. Internet ping
    $internet = Test-Ping $InternetTarget 4

    # 4. TCP 443
    $tcp = [ordered]@{ target = $DnsName; port = 443; connected = $false; ms = $null }
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $task = $client.ConnectAsync($DnsName, 443)
        if ($task.Wait(3000)) {
            $sw.Stop()
            $tcp.connected = $client.Connected
            if ($tcp.connected) { $tcp.ms = [math]::Round($sw.Elapsed.TotalMilliseconds, 0) }
        }
    } catch {
    } finally {
        $client.Close()
    }

    # 5. Traceroute (numeric, 15 hops max, 500 ms per probe)
    $hops = @()
    $completed = $false
    $lines = & tracert.exe -d -h 15 -w 500 $InternetTarget 2>&1
    foreach ($line in $lines) {
        $text = [string]$line
        if ($text -match "Trace complete") { $completed = $true }
        if ($text -match "^\s*(\d+)\s") {
            $hopNo = [int]$Matches[1]
            $addr = $null
            $ms = $null
            if ($text -match "(\d{1,3}(?:\.\d{1,3}){3})\s*$") { $addr = $Matches[1] }
            if ($text -match "(<?\d+)\s+ms") { $ms = $Matches[1] }
            $hops += [ordered]@{ hop = $hopNo; address = $addr; ms = $ms }
        }
    }

    [ordered]@{
        hostname   = $env:COMPUTERNAME
        gateway    = $gateway
        dns        = $dns
        internet   = $internet
        tcp443     = $tcp
        traceroute = [ordered]@{ target = $InternetTarget; completed = $completed; hops = @($hops) }
    }
}

# ---------------------------------------------------------------------------
# Run the probes
# ---------------------------------------------------------------------------
$Result = $null
$Status = "OK"
$Detail = $null

if ($IsRemote) {
    try {
        $Cred = Get-DiagnosticCred
    }
    catch {
        Submit-FailureAndExit $_.Exception.Message
    }

    try {
        $SessionOpt = New-PSSessionOption `
            -OpenTimeout ($WinRmOpenTimeoutSec * 1000) `
            -OperationTimeout ($WinRmOperationTimeoutSec * 1000)

        $Result = Invoke-Command `
            -ComputerName $ComputerName `
            -Credential $Cred `
            -SessionOption $SessionOpt `
            -ScriptBlock $ProbeBlock `
            -ArgumentList $DnsTestName, $InternetTarget `
            -ErrorAction Stop
    }
    catch {
        $Status = "WINRM_UNAVAILABLE"
        $Detail = "Could not run probes on $ComputerName over WinRM: $($_.Exception.Message)"
        Write-Host "WARNING: $Detail" -ForegroundColor Yellow
    }
}
else {
    try {
        $Result = & $ProbeBlock $DnsTestName $InternetTarget
    }
    catch {
        Submit-FailureAndExit "Local probes failed: $($_.Exception.Message)"
    }
}

if ($Status -eq "OK" -and -not $Result) {
    Submit-FailureAndExit "The probes returned no data from $ComputerName."
}

# ---------------------------------------------------------------------------
# Build and submit the report
# ---------------------------------------------------------------------------
$ReportHostname = if ($Result -and $Result.hostname) { [string]$Result.hostname }
                  elseif (-not $IsRemote) { $env:COMPUTERNAME }
                  else { $null }
$ReportIp = if ($ComputerName -match '^\d{1,3}(\.\d{1,3}){3}$') { $ComputerName } else { $null }

$Payload = [ordered]@{
    jobId      = $JobId
    endpoint   = [ordered]@{ mac = $Mac; hostname = $ReportHostname; ip = $ReportIp }
    status     = $Status
    detail     = $Detail
    gateway    = $Result.gateway
    dns        = $Result.dns
    internet   = $Result.internet
    tcp443     = $Result.tcp443
    traceroute = $Result.traceroute
} | ConvertTo-Json -Depth 8

$ApiKey = $env:POSTURE_API_KEY
$SubmitHeaders = @{}

if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    Write-Host "WARNING: POSTURE_API_KEY is not set in this session - the submit below will be rejected. Set it with `$env:POSTURE_API_KEY = '<key>'." -ForegroundColor Yellow
}
else {
    $SubmitHeaders["X-Posture-Api-Key"] = $ApiKey.Trim()
}

try {
    $Resp = Invoke-RestMethod `
        -Uri "$PostureAppBase/api/v1/diagnostics" `
        -Method Post `
        -Body $Payload `
        -ContentType "application/json" `
        -Headers $SubmitHeaders `
        -TimeoutSec $SubmitTimeoutSec

    Write-Host "Submitted diagnostics for $ComputerName ($Mac): status=$($Resp.status) score=$($Resp.score) band=$($Resp.band)" -ForegroundColor Green

    $ResultOut = [ordered]@{
        jobId     = $JobId
        computer  = $ComputerName
        mac       = $Mac
        status    = $Resp.status
        detail    = "score=$($Resp.score) band=$($Resp.band)"
        submitted = $true
    }
    Write-Output ("RESULT_JSON:" + ($ResultOut | ConvertTo-Json -Compress))
}
catch {
    $StatusCode = $null
    $ResponseBody = $null
    if ($_.Exception.Response) {
        $StatusCode = [int]$_.Exception.Response.StatusCode
        try {
            $Stream = $_.Exception.Response.GetResponseStream()
            $Reader = New-Object System.IO.StreamReader($Stream)
            $ResponseBody = $Reader.ReadToEnd()
        } catch { }
    }

    Write-Host "ERROR submitting diagnostics: $($_.Exception.Message)" -ForegroundColor Red
    if ($StatusCode) { Write-Host "HTTP status: $StatusCode" -ForegroundColor Red }
    if ($ResponseBody) { Write-Host "Response body: $ResponseBody" -ForegroundColor Red }

    $ResultOut = [ordered]@{
        jobId       = $JobId
        computer    = $ComputerName
        mac         = $Mac
        status      = "ERROR"
        detail      = "Probed OK but could not submit: $($_.Exception.Message)"
        submitted   = $false
        submitError = $_.Exception.Message
        httpStatus  = $StatusCode
    }
    Write-Output ("RESULT_JSON:" + ($ResultOut | ConvertTo-Json -Compress))
    exit 1
}