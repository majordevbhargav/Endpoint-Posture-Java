<#
.SYNOPSIS
    Endpoint Hardware Health collector - Phase 3a (project plan Section 6.1).

.DESCRIPTION
    Wraps the same PowerShell/CIM collection approach already proven in
    the original Python collector (identity, CPU/memory, storage health,
    battery, hardware events), but POSTs the report to the Spring Boot
    backend's /api/v1/hardware-health endpoint so results land in the
    shared Postgres database. JobWorker launches this once per
    HARDWARE_CHECK job via ProcessBuilder, the same way it launches
    posture_agent.ps1 for POSTURE_CHECK jobs, passing -ComputerName,
    -JobId, -PostureAppBase, and timeouts from application.yml.

    Supports BOTH local and REMOTE collection:
      - Local (default, no -ComputerName): runs directly on this machine,
        same as the original collector.
      - Remote (-ComputerName <target>): connects over WinRM/CIM using the
        same DCOM-then-WSMan fallback pattern already implemented in
        posture_agent.ps1 - including that file's -OperationTimeoutSec fix,
        so a hung/unreachable target fails fast with a real error instead
        of hanging. Credentials are loaded the same way posture_agent.ps1
        does: an explicit -Username/-Password/-PlainPassword override, or
        the shared DPAPI-encrypted common credential
        (Save-PostureCredential.ps1's posture_common_cred.xml) if none is
        given.

    This lets Hardware Health be collected centrally from the console the
    same way posture checks already are, rather than needing to be
    deployed to every endpoint individually. Warranty CSV/API lookup
    (Section 15, question 10) is still deferred until that question is
    answered.

.PARAMETER PostureAppBase
    Base URL of the backend (no path). The script appends
    /api/v1/hardware-health. JobWorker passes it from
    app.hardware.server-base-url in application.yml.

.PARAMETER SubmitTimeoutSec
    Timeout for the HTTP POST back to the backend, in seconds
    (app.hardware.submit-timeout-seconds).

.PARAMETER ComputerName
    Machine to collect from. Defaults to this machine, which means
    "local run". Anything else, including this machine's own IP address,
    is treated as a REMOTE target.

.PARAMETER JobId
    The posture_job UUID that triggered this run. Optional. Echoed in
    RESULT_JSON.

.PARAMETER Username
    Optional explicit account for a remote target. Overrides the stored
    common credential.

.PARAMETER Password
    Password for -Username as a SecureString. Optional.

.PARAMETER PlainPassword
    Password for -Username as plain text. Optional. Convenient for manual
    tests, but it ends up in shell history.

.PARAMETER CommonCredPath
    Path of the DPAPI-encrypted credential created by
    Save-PostureCredential.ps1. Defaults to posture_common_cred.xml next
    to this script.

.PARAMETER CimOperationTimeoutSec
    Timeout for every CIM/WinRM operation, in seconds
    (app.hardware.cim-timeout-seconds). Must stay well below the outer
    process timeout that JobWorker enforces
    (app.hardware.process-timeout-seconds).

.OUTPUTS
    Human-readable progress on the console, plus one machine-readable line
    on stdout that starts with "RESULT_JSON:" followed by compact JSON.
    JobWorker keys off its "submitted" field (true means success). Exit
    code is 1 when the run failed or the submission failed.

.NOTES
    STATUS: the backend route POST /api/v1/hardware-health is not built
    yet (the hardware module is designed, not implemented). Until it
    exists, a HARDWARE_CHECK job collects the data and then fails at the
    submit step; JobWorker retries it with backoff and finally leaves it
    FAILED. Do not enqueue hardware jobs until that route exists.

    AUTHENTICATION: no API-key header is sent yet, because there is no
    route to send it to. When the route is added, send the same
    X-Posture-Api-Key header as posture_agent.ps1 does (from the
    POSTURE_API_KEY environment variable), have JobWorker set that
    variable for hardware jobs too, and permit the route for ROLE_AGENT in
    SecurityConfig.

    LOCAL VS REMOTE: -ComputerName is compared with $env:COMPUTERNAME.
    Only an exact (case-insensitive) match counts as local.

.EXAMPLE
    .\hardware_health_agent.ps1
    (local collection - runs against this machine, posts to localhost:8090)

.EXAMPLE
    .\hardware_health_agent.ps1 -ComputerName 10.66.1.12 -JobId 3f9e...c2a1
    (remote collection - uses the stored common credential automatically)

.EXAMPLE
    .\hardware_health_agent.ps1 -ComputerName 10.66.1.12 -Username Administrator -PlainPassword "secret"
#>

param(
    # Points at the Spring Boot backend's hardware-health ingestion
    # route, not the old Python posture_ui.py. Like posture_agent.ps1,
    # this is meant to be supplied explicitly by JobWorker on every
    # invocation (from application.yml under app.hardware.*) - the
    # default here only supports running this script by hand.
    [string]$PostureAppBase = "http://localhost:8090",
    [int]$SubmitTimeoutSec = 20,

    # Defaults to the local machine - same "local first, then remote"
    # story as posture_agent.ps1.
    [string]$ComputerName = $env:COMPUTERNAME,

    # Carried through into RESULT_JSON so JobWorker can link this run
    # back to the posture_job row that triggered it. Optional.
    [string]$JobId,

    [string]$Username,
    [securestring]$Password,
    [string]$PlainPassword,
    [string]$CommonCredPath = "$PSScriptRoot\posture_common_cred.xml",

    # Explicit, shorter-than-outer-process CIM timeout - exposed as a
    # parameter so JobWorker can tune it from application.yml, same
    # rationale as posture_agent.ps1.
    [int]$CimOperationTimeoutSec = 30
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$IsRemote = $ComputerName -ne $env:COMPUTERNAME
$CimParams = @{}
$Session = $null
$Cred = $null

# ---------------------------------------------------------------------------
# Credential loading - same logic as posture_agent.ps1's Get-PostureCred,
# reused so both agents behave identically against the same shared common
# credential (and give the same requalification warnings).
# ---------------------------------------------------------------------------

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

    # No partial report is submitted here - /api/v1/hardware-health requires
    # a MAC address to accept a report at all, and at this stage of
    # collection we may not have one yet. Matches posture_agent.ps1's own
    # behavior on a connection failure: print the real cause clearly,
    # emit a RESULT_JSON line so JobWorker can fail the job with the real
    # reason, and exit non-zero rather than attempt a doomed submission.
    # (JobWorker writes a fallback ERROR assessment only for POSTURE_CHECK
    # jobs. Hardware jobs have no assessment row, so the reason is kept
    # on the job's error_message instead.)
    Write-Host ""
    Write-Host "ERROR: $Detail" -ForegroundColor Red

    $FailResult = [ordered]@{
        jobId     = $JobId
        computer  = $ComputerName
        status    = "ERROR"
        detail    = $Detail
        submitted = $false
    }

    Write-Output (
        "RESULT_JSON:" +
        ($FailResult | ConvertTo-Json -Compress)
    )

    exit 1
}

# ---------------------------------------------------------------------------
# Establish the CIM session for remote targets.
#
# Same DCOM-then-WSMan fallback pattern as posture_agent.ps1, including that
# file's -OperationTimeoutSec fix: without an explicit operation timeout, a
# target whose WinRM listener is up but not actually responding can hang for
# WinRM's own long default instead of failing fast with a real error.
# ---------------------------------------------------------------------------

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
        manufacturer   = $cs.Manufacturer
        model          = $cs.Model
        serial_number  = $csp.IdentifyingNumber
        bios_version   = $bios.SMBIOSBIOSVersion
        mac            = $nic.MACAddress
        hostname       = $reportHostname
        ip             = ($nic.IPAddress | Where-Object { $_ -and $_ -notmatch ':' } | Select-Object -First 1)
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
    # Get-PhysicalDisk is a storage cmdlet, not a plain CIM class lookup -
    # it takes -CimSession directly (same parameter name) rather than the
    # @CimParams splat used for Get-CimInstance above, so it's called
    # explicitly for the remote case.
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
    # Get-WinEvent is not a CIM cmdlet - it uses -ComputerName/-Credential
    # directly (EventLog remoting, not WinRM/DCOM), and needs the Remote
    # Event Log Management firewall rule enabled on the target. Failures
    # here are non-fatal to the rest of the report - just recorded as zero
    # events with a note, same "best effort" spirit as posture_agent.ps1's
    # optional collection sections.
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

    # --- Warranty (Section 15 question 10 pending; UNKNOWN until answered) --
    $warranty = @{ status = "UNKNOWN"; days_remaining = $null; reason = "Warranty data source not yet configured (see project plan Section 15, question 10)." }

    # --- Proactive recommendations (mirrors the original collector) -------
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
        jobId                       = $JobId
        endpoint                    = $identity
        cpu_memory                  = $cpuMemory
        storage                     = $storage
        battery                     = $battery
        hardware_events             = $events
        warranty                    = $warranty
        proactive_recommendations   = $recommendations
    }

    $payload = $report | ConvertTo-Json -Depth 8

    # NOTE: this route does not exist in the backend yet, so the call
    # below currently fails (expect a 404 or 401). It also sends no
    # X-Posture-Api-Key header yet - see .NOTES in the help block above.
    try {
        $resp = Invoke-RestMethod -Uri "$PostureAppBase/api/v1/hardware-health" -Method Post -Body $payload -ContentType "application/json" -TimeoutSec $SubmitTimeoutSec
        Write-Host "Submitted hardware health for $($identity.hostname) ($($identity.mac)): overall_score=$($resp.overall_score) band=$($resp.band)" -ForegroundColor Green

        $ResultOut = [ordered]@{
            jobId     = $JobId
            computer  = $identity.hostname
            mac       = $identity.mac
            status    = "COMPLIANT"
            detail    = "overall_score=$($resp.overall_score) band=$($resp.band)"
            submitted = $true
        }
        Write-Output ("RESULT_JSON:" + ($ResultOut | ConvertTo-Json -Compress))
    } catch {
        Write-Host "ERROR submitting hardware health: $($_.Exception.Message)" -ForegroundColor Red

        # Collection itself succeeded - only the HTTP submission failed.
        # JobWorker uses submitted=false + detail here to fail the job with
        # this reason (retried with backoff while attempts remain).
        $ResultOut = [ordered]@{
            jobId       = $JobId
            computer    = $identity.hostname
            mac         = $identity.mac
            status      = "ERROR"
            detail      = "Collected OK but could not submit: $($_.Exception.Message)"
            submitted   = $false
            submitError = $_.Exception.Message
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