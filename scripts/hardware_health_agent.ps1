<#
.SYNOPSIS
    Endpoint Hardware Health collector - Phase 3a (project plan Section 6.1).

.DESCRIPTION
    Wraps the same PowerShell/CIM collection approach already proven in
    the original Python collector (identity, CPU/memory, storage health,
    battery, hardware events), but POSTs the report to the Spring Boot
    backend's /api/v1/hardware-health endpoint so results land in the
    shared Postgres database.

.NOTES
    AUTHENTICATION: the backend rejects unauthenticated hardware-health
    reports (same as posture ingestion). This script sends the shared
    secret from the POSTURE_API_KEY environment variable as the
    X-Posture-Api-Key header. For a manual run:
        $env:POSTURE_API_KEY = "<value of app.posture.api-key>"
        .\hardware_health_agent.ps1

    LOCAL VS REMOTE: -ComputerName is compared with $env:COMPUTERNAME.
    Only an exact (case-insensitive) match counts as local.

    FIELD NAMES: cpuMemory / hardwareEvents / proactiveRecommendations /
    serialNumber / biosVersion are camelCase to match the Java DTO
    (HardwareReportRequest), which binds JSON keys by exact record
    field name with no snake_case mapping.
#>

param(
    [string]$PostureAppBase = "http://localhost:8090",
    [int]$SubmitTimeoutSec = 20,
    [string]$ComputerName = $env:COMPUTERNAME,
    [string]$JobId,
    [string]$Username,
    [securestring]$Password,
    [string]$PlainPassword,
    [string]$CommonCredPath = "$PSScriptRoot\posture_common_cred.xml",
    [int]$CimOperationTimeoutSec = 30
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

# ComputerName may be an IP (JobWorker prefers the endpoint's stored IP
# over its hostname), so a plain hostname comparison isn't enough - it
# would misclassify this same machine as "remote" whenever dispatched by
# its own IP, sending it into Get-HardwareHealthCred's Read-Host prompt,
# which then fails outright under JobWorker's -NonInteractive launch.
$LocalIPs = @(
    Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty IPAddress
)
$IsRemote = ($ComputerName -ne $env:COMPUTERNAME) -and ($ComputerName -notin $LocalIPs) -and ($ComputerName -ne '127.0.0.1') -and ($ComputerName -ne 'localhost')
$CimParams = @{}
$Session = $null
$Cred = $null

function Get-HardwareHealthCred {
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
                if ($PrefixIsIp -and $Prefix -ne $ComputerName) {
                    Write-Host "Stored credential was saved scoped to $Prefix, not $ComputerName - re-qualifying for this target." -ForegroundColor DarkYellow
                }
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

if ($IsRemote) {
    try {
        $Cred = Get-HardwareHealthCred
    }
    catch {
        Submit-FailureAndExit $_.Exception.Message
    }

    try {
        $Session = New-CimSession `
            -ComputerName $ComputerName `
            -Credential $Cred `
            -OperationTimeoutSec $CimOperationTimeoutSec `
            -SessionOption (New-CimSessionOption -Protocol Dcom) `
            -ErrorAction Stop
    }
    catch {
        $DcomError = $_.Exception.Message
        Write-Host "DCOM connection failed ($DcomError), trying WSMan/Kerberos instead..." -ForegroundColor Yellow

        try {
            $Session = New-CimSession `
                -ComputerName $ComputerName `
                -Credential $Cred `
                -OperationTimeoutSec $CimOperationTimeoutSec `
                -ErrorAction Stop
        }
        catch {
            Submit-FailureAndExit "Could not connect to $ComputerName via DCOM or WSMan. Most likely causes: the account lacks admin rights on the target, wrong password/account locked, or WMI firewall rules are blocking it. WSMan error detail: $($_.Exception.Message)"
        }
    }

    $CimParams = @{ CimSession = $Session }
}

try {
    # --- Identity ---------------------------------------------------------
    $cs   = Get-CimInstance @CimParams -ClassName Win32_ComputerSystem -ErrorAction SilentlyContinue
    $csp  = Get-CimInstance @CimParams -ClassName Win32_ComputerSystemProduct -ErrorAction SilentlyContinue
    $bios = Get-CimInstance @CimParams -ClassName Win32_BIOS -ErrorAction SilentlyContinue
    $nic  = Get-CimInstance @CimParams -ClassName Win32_NetworkAdapterConfiguration -Filter "IPEnabled=True" -ErrorAction SilentlyContinue |
        Select-Object -First 1

    $reportHostname = if ($IsRemote) { $ComputerName } else { $env:COMPUTERNAME }

    $identity = @{
        manufacturer = $cs.Manufacturer
        model        = $cs.Model
        serialNumber = $csp.IdentifyingNumber
        biosVersion  = $bios.SMBIOSBIOSVersion
        mac          = $nic.MACAddress
        hostname     = $reportHostname
        ip           = ($nic.IPAddress | Where-Object { $_ -and $_ -notmatch ':' } | Select-Object -First 1)
    }

    if (-not $identity.mac) {
        Submit-FailureAndExit "Connected to $ComputerName but could not determine its MAC address (no IP-enabled network adapter found) - cannot submit a hardware health report without one."
    }

    # --- CPU / Memory -------------------------------------------------------
    $cpu = Get-CimInstance @CimParams -ClassName Win32_Processor -ErrorAction SilentlyContinue |
        Select-Object -First 1 Name, LoadPercentage
    $os = Get-CimInstance @CimParams -ClassName Win32_OperatingSystem -ErrorAction SilentlyContinue
    $memUsedPct = $null
    if ($os.TotalVisibleMemorySize) {
        $memUsedPct = [math]::Round((($os.TotalVisibleMemorySize - $os.FreePhysicalMemory) / $os.TotalVisibleMemorySize) * 100, 1)
    }

    $cpuMemory = @{
        cpu    = @{ LoadPercentage = $cpu.LoadPercentage }
        memory = @{ UsedPercent = $memUsedPct }
    }

    # --- Storage --------------------------------------------------------
    $disks = if ($IsRemote) {
        @(Get-PhysicalDisk -CimSession $Session -ErrorAction SilentlyContinue | Select-Object FriendlyName, HealthStatus, MediaType)
    } else {
        @(Get-PhysicalDisk -ErrorAction SilentlyContinue | Select-Object FriendlyName, HealthStatus, MediaType)
    }
    $storage = @{ physical_disks = $disks }

    # --- Battery ----------------------------------------------------------
    $batteryStatic = @(Get-CimInstance @CimParams -Namespace root/wmi -ClassName BatteryStaticData -ErrorAction SilentlyContinue |
        Select-Object DesignedCapacity, FullChargedCapacity)
    $battery = @{ battery_static = $batteryStatic }

    # --- Hardware events (7 days) ------------------------------------------
    $hwEventsError = $null
    try {
        $winEventParams = @{
            FilterHashtable = @{ LogName = 'System'; StartTime = (Get-Date).AddDays(-7) }
            ErrorAction     = 'Stop'
        }
        if ($IsRemote) {
            $winEventParams.ComputerName = $ComputerName
            $winEventParams.Credential = $Cred
        }
        $hwEvents = @(Get-WinEvent @winEventParams | Where-Object { $_.ProviderName -match 'WHEA|disk|storport|stornvme|Ntfs|Kernel-Power|Display|USB' })
    }
    catch {
        $hwEvents = @()
        $hwEventsError = $_.Exception.Message
        Write-Host "WARNING: Could not read hardware event log on $ComputerName ($hwEventsError). Recording zero events for this run." -ForegroundColor Yellow
    }
    $events = @{ lookback_days = 7; event_count = $hwEvents.Count; collection_error = $hwEventsError }

    # --- Warranty (pending) --------------------------------------------------
    $warranty = @{ status = "UNKNOWN"; days_remaining = $null; reason = "Warranty data source not yet configured (see project plan Section 15, question 10)." }

    # --- Proactive recommendations -------------------------------------------
    $recommendations = @()
    foreach ($d in $disks) {
        if ($d.HealthStatus -and $d.HealthStatus -notin @("Healthy", "0")) {
            $recommendations += @{ priority = "HIGH"; area = "Storage"; action = "Investigate SSD/HDD health and schedule backup/replacement." }
        }
    }
    if ($events.event_count -ge 20) {
        $recommendations += @{ priority = "HIGH"; area = "Hardware Events"; action = "$($events.event_count) hardware-related events in 7 days; investigate before failure." }
    } elseif ($events.event_count -ge 5) {
        $recommendations += @{ priority = "MEDIUM"; area = "Hardware Events"; action = "$($events.event_count) hardware-related events in 7 days; monitor trend." }
    }

    $report = @{
        jobId                    = $JobId
        endpoint                 = $identity
        cpuMemory                = $cpuMemory
        storage                  = $storage
        battery                  = $battery
        hardwareEvents           = $events
        warranty                 = $warranty
        proactiveRecommendations = $recommendations
    }

    $payload = $report | ConvertTo-Json -Depth 8

    # ---------------------------------------------------------------------
    # Submit - authenticated the same way posture_agent.ps1 authenticates.
    # ---------------------------------------------------------------------
    $ApiKey = $env:POSTURE_API_KEY
    $SubmitHeaders = @{}

    if ([string]::IsNullOrWhiteSpace($ApiKey)) {
        Write-Host "WARNING: POSTURE_API_KEY is not set in this session - the submit below will get 401/403'd. Set it with `$env:POSTURE_API_KEY = '<key>'` before running this script." -ForegroundColor Yellow
    }
    else {
        $SubmitHeaders["X-Posture-Api-Key"] = $ApiKey.Trim()
        Write-Host "Using POSTURE_API_KEY (length=$($ApiKey.Trim().Length)) for submission." -ForegroundColor DarkGray
    }

    try {
        $resp = Invoke-RestMethod `
            -Uri "$PostureAppBase/api/v1/hardware-health" `
            -Method Post `
            -Body $payload `
            -ContentType "application/json" `
            -Headers $SubmitHeaders `
            -TimeoutSec $SubmitTimeoutSec

        Write-Host "Submitted hardware health for $($identity.hostname) ($($identity.mac)): overall_score=$($resp.overallScore) band=$($resp.overallBand)" -ForegroundColor Green

        $ResultOut = [ordered]@{
            jobId     = $JobId
            computer  = $identity.hostname
            mac       = $identity.mac
            status    = $resp.overallBand
            detail    = "overall_score=$($resp.overallScore) band=$($resp.overallBand)"
            submitted = $true
        }
        Write-Output ("RESULT_JSON:" + ($ResultOut | ConvertTo-Json -Compress))
    } catch {
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

        Write-Host "ERROR submitting hardware health: $($_.Exception.Message)" -ForegroundColor Red
        if ($StatusCode) { Write-Host "HTTP status: $StatusCode" -ForegroundColor Red }
        if ($ResponseBody) { Write-Host "Response body: $ResponseBody" -ForegroundColor Red }

        $ResultOut = [ordered]@{
            jobId       = $JobId
            computer    = $identity.hostname
            mac         = $identity.mac
            status      = "ERROR"
            detail      = "Collected OK but could not submit: $($_.Exception.Message)"
            submitted   = $false
            submitError = $_.Exception.Message
            httpStatus  = $StatusCode
        }
        Write-Output ("RESULT_JSON:" + ($ResultOut | ConvertTo-Json -Compress))

        exit 1
    }
}
finally {
    if ($Session) {
        Remove-CimSession $Session
    }
}