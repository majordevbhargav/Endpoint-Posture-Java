<#
.SYNOPSIS
    Cisco ISE Posture Agent - Windows Firewall / Open Ports / Application
    Control check.

.DESCRIPTION
    Checks the local or remote Windows device, collects:
      - Windows OS information
      - Primary IP/MAC information
      - Windows Firewall profile state
      - TCP listening ports (+ evaluated against an allow/block list)
      - Installed applications (+ evaluated against required/blocked lists)

    Remote connections try CIM/DCOM first and automatically fall back
    to CIM/WSMan.

    Invocation model: JobWorker (Spring Boot) launches this script once
    per posture_job via ProcessBuilder, passing -ComputerName, -JobId,
    -PostureServer, and the CIM/HTTP timeouts explicitly - all sourced
    from application.yml, never hardcoded on the Java side. With no
    arguments at all it runs against the local machine and posts to
    localhost:8090, so the whole collect -> evaluate -> submit pipeline
    can be validated by hand before JobWorker ever dispatches a real job.

    Results are POSTed to POST /api/v1/posture (PostureIngestController)
    and a RESULT_JSON line is always emitted on stdout so JobWorker can
    reliably parse the outcome even when the HTTP submission itself
    failed (see the RESULT_JSON section near the end of this script).

.PARAMETER PostureServer
    Full URL of the backend's posture ingestion route. JobWorker passes it
    from app.posture.server-url in application.yml.

.PARAMETER PostureServerTimeoutSec
    Timeout for the HTTP POST back to the backend, in seconds
    (app.posture.server-timeout-seconds).

.PARAMETER ComputerName
    Machine to check. Defaults to this machine, which means "local run".
    Anything else, including this machine's own IP address, is treated as
    a REMOTE target and goes through the CIM session and credential logic.

.PARAMETER JobId
    The posture_job UUID that triggered this run. Optional. Echoed in the
    submitted report and in RESULT_JSON so the assessment can be linked
    back to the job.

.PARAMETER Username
    Optional explicit account for a remote target. Overrides the stored
    common credential.

.PARAMETER Password
    Password for -Username as a SecureString. Optional.

.PARAMETER PlainPassword
    Password for -Username as plain text. Optional. Convenient for manual
    tests, but it ends up in shell history, so prefer -Password or the
    stored credential.

.PARAMETER CommonCredPath
    Path of the DPAPI-encrypted credential created by
    Save-PostureCredential.ps1. Defaults to posture_common_cred.xml next
    to this script.

.PARAMETER CimOperationTimeoutSec
    Timeout for every CIM/WinRM operation, in seconds
    (app.posture.cim-timeout-seconds). Must stay well below the outer
    process timeout that JobWorker enforces
    (app.posture.process-timeout-seconds).

.PARAMETER AllowedPorts
    Legacy. No longer used to decide compliance (ports are now judged by
    real reachability). Kept so older callers that still pass it do not break.

.PARAMETER BlockedPorts
    Legacy. Same as -AllowedPorts.

.PARAMETER PortProbeTimeoutMs
    Maximum time to wait on each per-port reachability probe, in
    milliseconds. One probe runs per listening port, one after another.

.PARAMETER RequiredApps
    Applications that must be installed. A missing one makes the
    APPLICATIONS check NON_COMPLIANT. Matched as a substring of the
    installed program's display name.

.PARAMETER BlockedApps
    Applications that must NOT be installed. A present one makes the
    APPLICATIONS check NON_COMPLIANT. Matched the same way.

.OUTPUTS
    Human-readable progress on the console, plus one machine-readable line
    on stdout that starts with "RESULT_JSON:" followed by compact JSON.
    JobWorker keys off its "submitted" field:
      submitted = true   the backend accepted the report; job is COMPLETE
      submitted = false  the run failed or the POST failed; the job is
                         failed and an ERROR assessment is recorded
    Process exit code is 1 only when collection itself failed. A failed
    HTTP submission still exits 0 and is reported through RESULT_JSON.

.NOTES
    AUTHENTICATION: the backend rejects unauthenticated posture reports.
    This script sends the shared secret from the POSTURE_API_KEY
    environment variable as the X-Posture-Api-Key header. JobWorker sets
    that variable for the child process from app.posture.api-key (or the
    POSTURE_API_KEY environment variable of the backend). For a manual run:
        $env:POSTURE_API_KEY = "<value of app.posture.api-key>"
        .\posture_agent.ps1

    LOCAL VS REMOTE: -ComputerName is compared with $env:COMPUTERNAME.
    Only an exact (case-insensitive) match counts as local. A local run
    needs no credentials.

.EXAMPLE
    .\posture_agent.ps1
    (local run - checks this machine, posts to localhost:8090)

.EXAMPLE
    .\posture_agent.ps1 -ComputerName 10.66.1.12 -JobId 3f9e...c2a1 -PostureServer http://localhost:8090/api/v1/posture

.EXAMPLE
    .\posture_agent.ps1 -ComputerName 10.66.1.12 -AllowedPorts 80,443,3389 -BlockedApps "uTorrent","TeamViewer"
#>

param(
    # Every value below is meant to be supplied explicitly by JobWorker
    # on each invocation (read from application.yml under app.posture.*,
    # e.g. app.posture.server-url, app.posture.cim-timeout-seconds) -
    # nothing here should end up hardcoded on the Java side either. The
    # defaults below exist only so this script still runs stand-alone
    # for manual testing.
    [string]$PostureServer = "http://localhost:8090/api/v1/posture",
    [int]$PostureServerTimeoutSec = 15,

    # Defaults to the local machine, not a required parameter. That's
    # what makes "local first, then remote" possible end-to-end: run
    # this script with no -ComputerName at all to validate the full
    # collect -> evaluate -> submit pipeline against your own laptop
    # before JobWorker ever points it at a real WinRM target.
    [string]$ComputerName = $env:COMPUTERNAME,

    # Carried through untouched into RESULT_JSON and the submitted
    # payload so PostureIngestService can link the resulting assessment back
    # to the posture_job row that triggered it (assessment.job_id).
    # Optional - a manual run with no -JobId still works, it just
    # produces an assessment with no job link.
    [string]$JobId,

    [string]$Username,
    [securestring]$Password,
    [string]$PlainPassword,
    [string]$CommonCredPath = "$PSScriptRoot\posture_common_cred.xml",

    # Explicit, shorter-than-outer-process CIM timeout (the architecture's
    # non-negotiable rule: every WinRM/CIM operation must time out before
    # whatever process launched this script does). Exposed as a parameter
    # instead of hardcoded further down so JobWorker can tune it from
    # application.yml without touching this file.
    [int]$CimOperationTimeoutSec = 30,

    # ------------------------------------------------------------------
    # Policy inputs. These are simple defaults - move them to a shared
    # config/JSON file once you want per-org or per-group policy instead
    # of one policy for every device this agent checks.
    # ------------------------------------------------------------------
    # NOTE: no longer used to gate compliance - ports are now classified
    # by actual reachability (see "Open Ports" evaluation below) instead
    # of a static allow/block list. Kept only so existing callers that
    # still pass these flags don't break.
    [int[]]$AllowedPorts = @(80, 443, 3389, 445, 135, 5985),
    [int[]]$BlockedPorts = @(21, 23, 3306, 5900),

    # Max time to wait on each per-port reachability probe. Keep this
    # small - it runs once per listening port, sequentially, inside the
    # overall check.
    [int]$PortProbeTimeoutMs = 400,
    [string[]]$RequiredApps = @("Cisco Secure Client"),
    [string[]]$BlockedApps  = @("uTorrent", "TeamViewer")
)

$ErrorActionPreference = "Stop"

# ---------------------------------------------------------------------------
# Connection setup
#
# No file-based queue anymore - that belonged to the old Python
# posture_ui.py auto-worker draining pending_devices.txt. JobWorker now
# owns the queue (posture_job, SELECT ... FOR UPDATE SKIP LOCKED) and
# dispatches one job as one ProcessBuilder invocation of this script
# with an explicit -ComputerName. $ComputerName defaults to the local
# machine above, so this script still runs perfectly well stand-alone.
# ---------------------------------------------------------------------------

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

# ---------------------------------------------------------------------------
# Credential loading
# ---------------------------------------------------------------------------

<#
.SYNOPSIS
    Builds the credential used to connect to a REMOTE target.

.DESCRIPTION
    Order of preference:
      1. An explicit -Username/-Password/-PlainPassword override.
      2. The stored common credential (posture_common_cred.xml), which is
         re-qualified for this target when its domain/machine prefix is
         missing, ".", this machine's own name, or a different device's IP.
      3. An interactive prompt. This only works in a console; when JobWorker
         launches the script (-NonInteractive) there is nobody to answer, so
         the function throws instead.

    Only called for remote targets. A local run never needs a credential.

.OUTPUTS
    A PSCredential. Throws a readable message when no usable credential
    exists; the caller reports that message as the ERROR detail.
#>
function Get-PostureCred {
    $HasExplicitOverride = [bool](
        $Username -or
        $Password -or
        $PlainPassword
    )

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

            $PrefixIsIp = $Prefix -and (
                $Prefix -match '^\d{1,3}(\.\d{1,3}){3}$'
            )

            $NeedsRequalify =
                (-not $Prefix) -or
                ($Prefix -eq '.') -or
                ($Prefix -ieq $env:COMPUTERNAME) -or
                ($PrefixIsIp -and $Prefix -ne $ComputerName)

            if ($NeedsRequalify) {
                if ($PrefixIsIp -and $Prefix -ne $ComputerName) {
                    Write-Host `
                        "Stored credential was saved scoped to $Prefix, not $ComputerName - re-qualifying for this target." `
                        -ForegroundColor DarkYellow
                }

                $QualifiedStoredUser = "$ComputerName\$BareUser"

                $Stored = New-Object `
                    System.Management.Automation.PSCredential(
                        $QualifiedStoredUser,
                        $Stored.Password
                    )
            }

            Write-Host `
                "Using stored common credential ($($Stored.UserName)) for $ComputerName" `
                -ForegroundColor DarkGray

            return $Stored
        }
        catch {
            $ImportErr = $_.Exception.Message

            if (
                $ImportErr -match
                'Key not valid|invalid in the current context|padding is invalid|Cryptographic'
            ) {
                $script:CredLoadWarning =
                    "Could not decrypt the stored credential at $CommonCredPath. " +
                    "This almost always means it was saved under a different " +
                    "Windows user account/session. Re-run " +
                    "Save-PostureCredential.ps1 under the same account/session " +
                    "that runs posture_agent.ps1."
            }
            else {
                $script:CredLoadWarning =
                    "Could not load stored credential from $CommonCredPath : $ImportErr"
            }

            Write-Host `
                $script:CredLoadWarning `
                -ForegroundColor Yellow
        }
    }

    if (-not $Username) {
        try {
            $script:Username = Read-Host `
                "Username on $ComputerName (e.g. Administrator)"
        }
        catch {
            $Reason = if ($script:CredLoadWarning) {
                $script:CredLoadWarning
            }
            else {
                "No credential available, and this session cannot prompt interactively."
            }

            throw $Reason
        }
    }

    if ($PlainPassword) {
        $SecurePwd = New-Object System.Security.SecureString

        foreach ($ch in $PlainPassword.ToCharArray()) {
            $SecurePwd.AppendChar($ch)
        }

        $SecurePwd.MakeReadOnly()
    }
    elseif ($Password) {
        $SecurePwd = $Password
    }
    else {
        $SecurePwd = Read-Host `
            "Password for $Username" `
            -AsSecureString
    }

    $QualifiedUser = if ($Username -match '\\') {
        $Username
    }
    else {
        "$ComputerName\$Username"
    }

    return New-Object `
        System.Management.Automation.PSCredential(
            $QualifiedUser,
            $SecurePwd
        )
}

# ---------------------------------------------------------------------------
# Main posture collection
# ---------------------------------------------------------------------------

$CollectionSucceeded = $false
$OS = $null
$Nic = $null
$Compliant = $null
$Status = "ERROR"
$Detail = $null
$Ports = @()
$InstalledApps = @()

# Open Ports / Application Control evaluation results (populated below,
# defaulted here so the payload build never sees an undefined variable
# if collection fails before reaching those sections).
$OpenPortsStatus = "ERROR"
$OpenPortsDetail = "Not evaluated - collection did not complete."
$AppControlStatus = "ERROR"
$AppControlDetail = "Not evaluated - collection did not complete."

try {
    if ($IsRemote) {
        try {
            $Cred = Get-PostureCred
        }
        catch {
            Write-Host `
                "ERROR: $($_.Exception.Message)" `
                -ForegroundColor Red

            $FailResult = [ordered]@{
                jobId     = $JobId
                computer  = $ComputerName
                compliant = $null
                status    = "ERROR"
                detail    = $_.Exception.Message
                submitted = $false
            }

            Write-Output (
                "RESULT_JSON:" +
                ($FailResult | ConvertTo-Json -Compress)
            )

            exit 1
        }

        # DCOM first.
        #
        # BUG FIX: neither the DCOM nor the WSMan New-CimSession call below
        # used to set an explicit operation timeout. A target whose WinRM
        # listener is up but not actually answering (service hung, firewall
        # now silently dropping rather than rejecting) can then hang for
        # WinRM's own long default operation timeout (3 minutes) on EVERY
        # CIM call made afterward - OS info, firewall, ports, apps, etc.
        # posture_ui.py's outer subprocess.run() only waits
        # $CIM_SUBPROCESS_TIMEOUT_SECONDS before killing the whole process
        # tree, so whichever timeout lost that race fired first, and if it
        # was the outer Python one, whatever real WinRM/DCOM error text
        # PowerShell was about to produce never got written out - producing
        # exactly the generic "Timed out waiting for the check to finish."
        # symptom with no underlying cause, even for endpoints that were
        # compliant on a previous run (something changed target-side and it
        # now hangs instead of failing fast). Mirrors the same fix already
        # applied to application_remediation.py's Invoke-Command sessions.
        # (Value now comes from the -CimOperationTimeoutSec parameter above,
        # not hardcoded, so JobWorker can tune it via application.yml.)
        #
        # In the Java rewrite the outer limit is app.posture.process-timeout-seconds,
        # enforced by JobWorker (it kills this process when exceeded). Keep
        # -CimOperationTimeoutSec comfortably below it so this script can
        # still report a real error before it is killed.

        try {
            $Session = New-CimSession `
                -ComputerName $ComputerName `
                -Credential $Cred `
                -OperationTimeoutSec $CimOperationTimeoutSec `
                -SessionOption (
                    New-CimSessionOption -Protocol Dcom
                ) `
                -ErrorAction Stop
        }
        catch {
            $DcomError = $_.Exception.Message

            Write-Host `
                "DCOM connection failed ($DcomError), trying WSMan/Kerberos instead..." `
                -ForegroundColor Yellow

            try {
                $Session = New-CimSession `
                    -ComputerName $ComputerName `
                    -Credential $Cred `
                    -OperationTimeoutSec $CimOperationTimeoutSec `
                    -ErrorAction Stop
            }
            catch {
                Write-Host ""
                Write-Host `
                    "ERROR: Could not connect to $ComputerName via DCOM or WSMan." `
                    -ForegroundColor Red

                Write-Host `
                    "Most likely causes:" `
                    -ForegroundColor Red

                Write-Host `
                    "  1. The account does not have real admin rights on $ComputerName." `
                    -ForegroundColor Red

                Write-Host `
                    "  2. Wrong password or account locked out." `
                    -ForegroundColor Red

                Write-Host `
                    "  3. WMI firewall rules are blocked on the target." `
                    -ForegroundColor Red

                Write-Host ""
                Write-Host `
                    "WSMan error detail: $($_.Exception.Message)" `
                    -ForegroundColor DarkGray

                $FailResult = [ordered]@{
                    jobId     = $JobId
                    computer  = $ComputerName
                    compliant = $null
                    status    = "ERROR"
                    detail    = "Could not connect via DCOM or WSMan: $($_.Exception.Message)"
                    submitted = $false
                }

                Write-Output (
                    "RESULT_JSON:" +
                    ($FailResult | ConvertTo-Json -Compress)
                )

                exit 1
            }
        }

        $CimParams = @{
            CimSession = $Session
        }
    }

    try {
        # ---------------------------------------------------------------
        # OS
        # ---------------------------------------------------------------

        $OS = Get-CimInstance `
            @CimParams `
            -ClassName Win32_OperatingSystem

        # ---------------------------------------------------------------
        # Network adapter
        # ---------------------------------------------------------------

        $Nic = Get-CimInstance `
            @CimParams `
            -ClassName Win32_NetworkAdapterConfiguration `
            -Filter "IPEnabled=True" |
            Where-Object {
                $_.DefaultIPGateway
            } |
            Select-Object -First 1

        if (-not $Nic) {
            $Nic = Get-CimInstance `
                @CimParams `
                -ClassName Win32_NetworkAdapterConfiguration `
                -Filter "IPEnabled=True" |
                Select-Object -First 1
        }

        if (-not $Nic) {
            throw "No IP-enabled network adapter was found on $ComputerName."
        }

        # ---------------------------------------------------------------
        # Windows Firewall
        # ---------------------------------------------------------------

        $FwDisabled = Get-CimInstance `
            @CimParams `
            -Namespace ROOT\StandardCimv2 `
            -ClassName MSFT_NetFirewallProfile |
            Where-Object {
                -not $_.Enabled
            }

        $Compliant = $FwDisabled.Count -eq 0

        $Status = if ($Compliant) {
            "COMPLIANT"
        }
        else {
            "NON_COMPLIANT"
        }

        $Detail = if ($Compliant) {
            "All firewall profiles enabled"
        }
        else {
            $DisabledNames = @(
                $FwDisabled |
                Select-Object -ExpandProperty Name |
                Where-Object { $_ }
            )

            "Disabled: " + ($DisabledNames -join ", ")
        }

        # ---------------------------------------------------------------
        # Hardware inventory (manufacturer / model / serial number)
        # ---------------------------------------------------------------

        $HardwareInfo = @{
            manufacturer  = $null
            model         = $null
            serial_number = $null
        }

        try {
            $CS = Get-CimInstance `
                @CimParams `
                -ClassName Win32_ComputerSystem

            $BiosInfo = Get-CimInstance `
                @CimParams `
                -ClassName Win32_BIOS

            $HardwareInfo.manufacturer  = $CS.Manufacturer
            $HardwareInfo.model         = $CS.Model
            $HardwareInfo.serial_number = $BiosInfo.SerialNumber
        }
        catch {
            Write-Host `
                "WARNING: Failed to collect hardware info: $($_.Exception.Message)" `
                -ForegroundColor Yellow
        }

        # ---------------------------------------------------------------
        # CPU / memory utilization
        # ---------------------------------------------------------------
        #
        # $OS was already retrieved above with no -Property filter, so
        # its TotalVisibleMemorySize/FreePhysicalMemory (both in KB) are
        # already populated - no extra CIM round trip needed for those.

        $ResourceUsage = @{
            cpu_percent     = $null
            memory_percent  = $null
            memory_total_mb = $null
            memory_free_mb  = $null
        }

        try {
            $CpuLoad = (
                Get-CimInstance `
                    @CimParams `
                    -ClassName Win32_Processor |
                Measure-Object -Property LoadPercentage -Average
            ).Average

            if ($null -ne $CpuLoad) {
                $ResourceUsage.cpu_percent = [math]::Round($CpuLoad, 1)
            }

            $TotalMemKb = $OS.TotalVisibleMemorySize
            $FreeMemKb  = $OS.FreePhysicalMemory

            if ($TotalMemKb) {
                $ResourceUsage.memory_total_mb = [math]::Round($TotalMemKb / 1024, 1)
                $ResourceUsage.memory_free_mb  = [math]::Round($FreeMemKb / 1024, 1)
                $ResourceUsage.memory_percent  = [math]::Round(
                    (($TotalMemKb - $FreeMemKb) / $TotalMemKb) * 100,
                    1
                )
            }
        }
        catch {
            Write-Host `
                "WARNING: Failed to collect CPU/memory usage: $($_.Exception.Message)" `
                -ForegroundColor Yellow
        }

        # ---------------------------------------------------------------
        # Top processes by memory (CIM works remotely without WinRM;
        # Get-Process does not support -ComputerName on modern PowerShell)
        # ---------------------------------------------------------------

        $TopProcesses = @()

        try {
            # Only ask CIM for the 4 fields actually used below. Without
            # -Property, Win32_Process returns the FULL object (dozens
            # of fields, including things like CommandLine) for EVERY
            # process on the box, which then has to be serialized back
            # over DCOM/WSMan and sorted before being thrown away. That
            # is the single heaviest thing this agent does remotely, and
            # is almost certainly what pushed some checks over the 90s
            # timeout - trimming it to 4 fields cuts both the network
            # payload and the work on the target substantially.
            $TopProcesses = @(
                Get-CimInstance `
                    @CimParams `
                    -ClassName Win32_Process `
                    -Property Name, ProcessId, WorkingSetSize, UserModeTime, KernelModeTime |
                Sort-Object WorkingSetSize -Descending |
                Select-Object -First 10 |
                ForEach-Object {
                    @{
                        name      = $_.Name
                        pid       = $_.ProcessId
                        memory_mb = [math]::Round($_.WorkingSetSize / 1MB, 1)
                        cpu_time_seconds = if ($_.UserModeTime -and $_.KernelModeTime) {
                            [math]::Round((($_.UserModeTime + $_.KernelModeTime) / 1e7), 1)
                        } else {
                            $null
                        }
                    }
                }
            )
        }
        catch {
            Write-Host `
                "WARNING: Failed to collect process list: $($_.Exception.Message)" `
                -ForegroundColor Yellow
        }

        # ---------------------------------------------------------------
        # Listening TCP ports
        # ---------------------------------------------------------------

        try {
            $Connections = Get-CimInstance `
                @CimParams `
                -Namespace ROOT\StandardCimv2 `
                -ClassName MSFT_NetTCPConnection `
                -Filter "State=2" `
                -ErrorAction SilentlyContinue

            if ($Connections) {
                $Pids = $Connections |
                    Select-Object -ExpandProperty OwningProcess -Unique

                $ProcMap = @{}

                if ($Pids) {
                    $PidsFilter = (
                        $Pids |
                        ForEach-Object {
                            "ProcessId=$_"
                        }
                    ) -join " or "

                    $Processes = Get-CimInstance `
                        @CimParams `
                        -ClassName Win32_Process `
                        -Filter $PidsFilter `
                        -ErrorAction SilentlyContinue

                    foreach ($p in $Processes) {
                        $ProcMap[$p.ProcessId] = $p.Name
                    }
                }

                foreach ($c in $Connections) {
                    $ProcessId = $c.OwningProcess

                    $ProcessName = if (
                        $ProcMap.ContainsKey($ProcessId)
                    ) {
                        $ProcMap[$ProcessId]
                    }
                    else {
                        "Unknown"
                    }

                    $Ports += @{
                        port    = $c.LocalPort
                        process = $ProcessName
                        pid     = $ProcessId
                    }
                }
            }
        }
        catch {
            Write-Host `
                "WARNING: Failed to query listening ports: $($_.Exception.Message)" `
                -ForegroundColor Yellow
        }

        # ---------------------------------------------------------------
        # Installed applications (registry-based - see
        # Endpoint_Application_&Port.docx for the local-machine version
        # of this approach)
        # ---------------------------------------------------------------

        try {
            $AppCollectionMethod = $null
            $AppCollectionError  = $null

            if ($IsRemote) {
                # Get-ItemProperty doesn't traverse a CIM session, so for
                # remote targets we read the same uninstall keys through
                # StdRegProv over CIM instead.
                #
                # NOTE: StdRegProv's remote registry methods (EnumKey,
                # GetStringValue) depend on the target's "Remote Registry"
                # (RemoteRegistry) service. That service is Automatic on
                # Windows Server but DISABLED BY DEFAULT on Windows 10/11
                # client editions. When it's off, every call below fails
                # and - because each one used -ErrorAction SilentlyContinue
                # - $InstalledApps silently stays empty with no trace
                # anywhere. We now capture that failure explicitly instead
                # of swallowing it, and fall back to a method that doesn't
                # need Remote Registry at all (see below).
                $RegPaths = @(
                    'SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall',
                    'SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall'
                )

                try {
                    $Reg = Get-CimInstance `
                        -CimSession $Session `
                        -Namespace root\default `
                        -ClassName StdRegProv `
                        -ErrorAction Stop

                    if ($Reg) {
                        $HKLM = [uint32]2147483650

                        foreach ($RegPath in $RegPaths) {
                            $SubKeys = Invoke-CimMethod `
                                -InputObject $Reg `
                                -MethodName EnumKey `
                                -Arguments @{ hDefKey = $HKLM; sSubKeyName = $RegPath } `
                                -ErrorAction Stop

                            if ($SubKeys.ReturnValue -ne 0) {
                                throw "StdRegProv.EnumKey returned code $($SubKeys.ReturnValue) for '$RegPath' - the Remote Registry service on $ComputerName is likely stopped/disabled."
                            }

                            foreach ($Key in $SubKeys.sNames) {
                                $FullKey = "$RegPath\$Key"

                                $NameResult = Invoke-CimMethod `
                                    -InputObject $Reg `
                                    -MethodName GetStringValue `
                                    -Arguments @{ hDefKey = $HKLM; sSubKeyName = $FullKey; sValueName = "DisplayName" } `
                                    -ErrorAction SilentlyContinue

                                $Name = $NameResult.sValue

                                if ($Name) {
                                    $VersionResult = Invoke-CimMethod `
                                        -InputObject $Reg `
                                        -MethodName GetStringValue `
                                        -Arguments @{ hDefKey = $HKLM; sSubKeyName = $FullKey; sValueName = "DisplayVersion" } `
                                        -ErrorAction SilentlyContinue

                                    $PublisherResult = Invoke-CimMethod `
                                        -InputObject $Reg `
                                        -MethodName GetStringValue `
                                        -Arguments @{ hDefKey = $HKLM; sSubKeyName = $FullKey; sValueName = "Publisher" } `
                                        -ErrorAction SilentlyContinue

                                    $InstalledApps += @{
                                        name      = $Name
                                        version   = $VersionResult.sValue
                                        publisher = $PublisherResult.sValue
                                    }
                                }
                            }
                        }

                        if ($InstalledApps.Count -gt 0) {
                            $AppCollectionMethod = "stdregprov"
                        }
                    }
                    else {
                        throw "Get-CimInstance for StdRegProv returned nothing."
                    }
                }
                catch {
                    $AppCollectionError = $_.Exception.Message

                    Write-Host `
                        "WARNING: StdRegProv application collection failed ($AppCollectionError). Falling back to WinRM/Invoke-Command..." `
                        -ForegroundColor Yellow

                    # Fallback: PowerShell remoting (WinRM) runs the exact
                    # same native Get-ItemProperty registry read directly
                    # ON the target, so it needs WinRM (already required
                    # for the WSMan CIM fallback above) but NOT the
                    # separate Remote Registry service that StdRegProv
                    # depends on.
                    try {
                        $RemoteApps = Invoke-Command `
                            -ComputerName $ComputerName `
                            -Credential $Cred `
                            -ErrorAction Stop `
                            -ScriptBlock {
                                @(
                                    Get-ItemProperty "HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*" -ErrorAction SilentlyContinue
                                    Get-ItemProperty "HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*" -ErrorAction SilentlyContinue
                                ) |
                                Where-Object { $_.DisplayName } |
                                ForEach-Object {
                                    [PSCustomObject]@{
                                        name      = $_.DisplayName
                                        version   = $_.DisplayVersion
                                        publisher = $_.Publisher
                                    }
                                }
                            }

                        $InstalledApps = @(
                            $RemoteApps | ForEach-Object {
                                @{ name = $_.name; version = $_.version; publisher = $_.publisher }
                            }
                        )

                        if ($InstalledApps.Count -gt 0) {
                            $AppCollectionMethod = "winrm"
                            $AppCollectionError = $null
                        }
                    }
                    catch {
                        $AppCollectionError = "$AppCollectionError | WinRM fallback also failed: $($_.Exception.Message)"

                        Write-Host `
                            "WARNING: WinRM application collection fallback also failed: $($_.Exception.Message)" `
                            -ForegroundColor Yellow
                    }
                }
            }
            else {
                $InstalledApps = @(
                    Get-ItemProperty "HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*" -ErrorAction SilentlyContinue
                    Get-ItemProperty "HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*" -ErrorAction SilentlyContinue
                ) |
                Where-Object { $_.DisplayName } |
                ForEach-Object {
                    @{
                        name      = $_.DisplayName
                        version   = $_.DisplayVersion
                        publisher = $_.Publisher
                    }
                }
            }

            $InstalledApps = @(
                $InstalledApps |
                Sort-Object { $_.name } -Unique
            )

            if (-not $IsRemote -and $InstalledApps.Count -gt 0) {
                $AppCollectionMethod = "local"
            }
        }
        catch {
            Write-Host `
                "WARNING: Failed to collect installed applications: $($_.Exception.Message)" `
                -ForegroundColor Yellow
        }

        $AppNames = @($InstalledApps | ForEach-Object { $_.name })

        # ---------------------------------------------------------------
        # Evaluate "Open Ports" check
        #
        # Rather than judging each listening port against a static
        # allow/block list, each port is actively probed from the
        # machine running this script:
        #
        #   - Open ports   = listening AND the probe connected. These
        #                    are genuinely reachable/working.
        #   - Blocked ports = listening locally, but the probe could
        #                    not connect (firewall/ACL/network path is
        #                    dropping or rejecting it).
        #
        # This is informational (both categories are valid outcomes
        # depending on intent), so it does not by itself flip the
        # compliance status - it gives visibility into which of the
        # device's listening services are actually reachable.
        #
        # NOTE: Test-NetConnection was tried first here and reverted -
        # it has no configurable connect timeout, and a genuinely
        # filtered/dropped port can make it hang for 10-20+ seconds
        # EACH. With several listening ports on a device, that alone
        # blew past the 90s subprocess timeout in posture_ui.py and
        # made previously-fine devices show up as ERROR/timeout. A raw
        # TcpClient probe with an explicit short timeout below fixes
        # that: each probe is capped at $PortProbeTimeoutMs.
        # ---------------------------------------------------------------

        $ListeningPortNumbers = @(
            $Ports |
            ForEach-Object { [int]$_.port } |
            Sort-Object -Unique
        )

        $OpenPorts = @()
        $BlockedPortsUnreachable = @()

        foreach ($PortNum in $ListeningPortNumbers) {
            $Reachable = $false
            $TcpClient = New-Object System.Net.Sockets.TcpClient

            try {
                $ConnectTask = $TcpClient.ConnectAsync($ComputerName, $PortNum)

                if ($ConnectTask.Wait($PortProbeTimeoutMs)) {
                    $Reachable = $TcpClient.Connected
                }
                else {
                    $Reachable = $false
                }
            }
            catch {
                $Reachable = $false
            }
            finally {
                $TcpClient.Close()
                $TcpClient.Dispose()
            }

            if ($Reachable) {
                $OpenPorts += $PortNum
            }
            else {
                $BlockedPortsUnreachable += $PortNum
            }
        }

        # Attach reachability onto each already-collected port/process
        # record so the UI can show process + reachability together.
        foreach ($PortEntry in $Ports) {
            $PortEntry.reachable = $OpenPorts -contains [int]$PortEntry.port
        }

        $OpenPortsStatus = "COMPLIANT"

        $OpenPortsDetail = (
            "Open/working: " +
            $(if ($OpenPorts.Count) { $OpenPorts -join ", " } else { "none" }) +
            " | Blocked/unreachable: " +
            $(if ($BlockedPortsUnreachable.Count) { $BlockedPortsUnreachable -join ", " } else { "none" })
        )

        # ---------------------------------------------------------------
        # Evaluate "Application Control" check
        #
        # NON-COMPLIANT if a required app is missing, or a blocked app
        # is present. Matching is substring-based ("-like *term*") since
        # DisplayName strings vary by version/vendor formatting.
        # ---------------------------------------------------------------

        $MissingRequired = @(
            $RequiredApps | Where-Object {
                $Req = $_
                -not ($AppNames | Where-Object { $_ -like "*$Req*" })
            }
        )

        $FoundBlocked = @(
            $BlockedApps | Where-Object {
                $Blk = $_
                ($AppNames | Where-Object { $_ -like "*$Blk*" })
            }
        )

        if ($InstalledApps.Count -eq 0 -and $AppCollectionError) {
            # We genuinely could not read the application inventory (see
            # $AppCollectionError above) - this is a collection failure,
            # not evidence that required apps are missing. Reporting it
            # as NON-COMPLIANT here would be a false compliance finding,
            # so it gets its own ERROR state instead.
            $AppControlStatus = "ERROR"
            $AppControlDetail =
                "Could not read the installed-application inventory from " +
                "$ComputerName (StdRegProv and WinRM both failed) - Application " +
                "Control was not evaluated this run. Likely cause: the " +
                "'Remote Registry' service is stopped/disabled on the " +
                "target, or WinRM is unreachable. Detail: $AppCollectionError"
        }
        else {
            $AppControlStatus = if ($MissingRequired.Count -eq 0 -and $FoundBlocked.Count -eq 0) {
                "COMPLIANT"
            }
            else {
                "NON_COMPLIANT"
            }

            $AppControlDetailParts = @()

            if ($MissingRequired.Count -gt 0) {
                $AppControlDetailParts += "Missing required: " + ($MissingRequired -join ", ")
            }

            if ($FoundBlocked.Count -gt 0) {
                $AppControlDetailParts += "Blocked apps found: " + ($FoundBlocked -join ", ")
            }

            $AppControlDetail = if ($AppControlDetailParts.Count -gt 0) {
                $AppControlDetailParts -join " | "
            }
            else {
                "Policy satisfied."
            }
        }

        # ---------------------------------------------------------------
        # Overall status
        #
        # $Status above only reflects the Windows Firewall check. The
        # value actually sent to the backend as the assessment's overall
        # status must reflect ALL three checks (firewall, ports, apps),
        # so it's computed here as its own step: ERROR outranks
        # NON_COMPLIANT (a check that couldn't run is worse than one
        # that ran and failed), which outranks COMPLIANT.
        # ---------------------------------------------------------------

        $AllCheckStatuses = @($Status, $OpenPortsStatus, $AppControlStatus)

        $OverallStatus = if ($AllCheckStatuses -contains "ERROR") {
            "ERROR"
        }
        elseif ($AllCheckStatuses -contains "NON_COMPLIANT") {
            "NON_COMPLIANT"
        }
        else {
            "COMPLIANT"
        }

        $CollectionSucceeded = $true
    }
    catch {
        Write-Host `
            "ERROR querying $ComputerName after connecting: $($_.Exception.Message)" `
            -ForegroundColor Red

        $OverallStatus = "ERROR"

        $FailResult = [ordered]@{
            jobId     = $JobId
            computer  = if ($OS) {
                $OS.CSName
            }
            else {
                $ComputerName
            }
            compliant = $null
            status    = "ERROR"
            detail    = "Connected, but the posture query itself failed: $($_.Exception.Message)"
            submitted = $false
        }

        Write-Output (
            "RESULT_JSON:" +
            ($FailResult | ConvertTo-Json -Compress)
        )

        exit 1
    }
}
finally {
    if ($Session) {
        Remove-CimSession $Session
    }
}

# ---------------------------------------------------------------------------
# Result display
# ---------------------------------------------------------------------------

$DisplayColor = if ($OverallStatus -eq "COMPLIANT") {
    "Green"
}
elseif ($OverallStatus -eq "ERROR") {
    "Yellow"
}
else {
    "Red"
}

Write-Host `
    "Host: $($OS.CSName)  MAC: $($Nic.MACAddress)  Firewall: $Status  Ports: $OpenPortsStatus  Apps: $AppControlStatus" `
    -ForegroundColor $DisplayColor

# ---------------------------------------------------------------------------
# Submit to the Spring Boot backend (PostureIngestController)
#
# Field names below match the DTO the backend deserializes into
# (PostureReportRequest; each entry of checks[] maps to CheckInput):
# one assessment (endpoint + overall status + jobId) with a `checks[]`
# array, each check carrying a `details` object that lands straight in
# check_result.details (JSONB) - no flattening needed on the Java side.
# ---------------------------------------------------------------------------

$PrimaryIPv4 = $null

if ($Nic.IPAddress) {
    $PrimaryIPv4 = @(
        $Nic.IPAddress |
        Where-Object {
            $_ -and $_ -notmatch ':'
        }
    ) | Select-Object -First 1
}

$AllIPs = @()

if ($Nic.IPAddress) {
    $AllIPs = @(
        $Nic.IPAddress |
        Where-Object {
            $_
        }
    )
}

$AllChecks = @(
    @{
        checkType = "FIREWALL"
        status    = $Status
        details   = @{ summary = $Detail }
    }
    @{
        checkType = "OPEN_PORTS"
        status    = $OpenPortsStatus
        details   = @{
            summary      = $OpenPortsDetail
            openPorts    = @($OpenPorts)
            blockedPorts = @($BlockedPortsUnreachable)
        }
    }
    @{
        checkType = "APPLICATIONS"
        status    = $AppControlStatus
        details   = @{
            summary          = $AppControlDetail
            collectionMethod = $AppCollectionMethod
            collectionError  = $AppCollectionError
        }
    }
)

$PayloadObject = @{
    jobId       = $JobId
    macAddress  = $Nic.MACAddress
    hostname    = $OS.CSName
    osName      = $OS.Caption
    osVersion   = $OS.Version
    ipAddress   = $PrimaryIPv4
    ipAddresses = $AllIPs
    collectedAt = (Get-Date).ToUniversalTime().ToString("o")
    status      = $OverallStatus

    hardware = @{
        manufacturer = $HardwareInfo.manufacturer
        model        = $HardwareInfo.model
        serialNumber = $HardwareInfo.serial_number
    }

    checks = $AllChecks

    # Raw facts, kept separate from the compliance checks above. They are
    # informational, not pass/fail, and are meant for future inventory
    # tables (apps/ports/processes) rather than check_result. The backend
    # accepts this block today (PostureReportRequest.InventoryDto) but
    # does not store it yet.
    inventory = @{
        listeningPorts = @($Ports)
        installedApps  = @($InstalledApps)
        topProcesses   = @($TopProcesses)
        resourceUsage  = $ResourceUsage
    }
}

$Payload = $PayloadObject |
    ConvertTo-Json -Depth 10

# Authentication: the backend only accepts posture reports from a caller
# that presents the shared agent key. JobWorker hands the key to this
# process in the POSTURE_API_KEY environment variable (never on the command
# line, where it would show up in process listings) and it is sent as the
# X-Posture-Api-Key header, checked by the backend's PostureApiKeyFilter.
# If the variable is not set no header is sent, the backend answers 401,
# and the failure is reported below as submitted=false with the HTTP error
# text. For a manual run:
#     $env:POSTURE_API_KEY = "<value of app.posture.api-key>"
$SubmitHeaders = @{}

if ($env:POSTURE_API_KEY) {
    $SubmitHeaders["X-Posture-Api-Key"] = $env:POSTURE_API_KEY
}

try {
    $Response = Invoke-RestMethod `
        -Uri $PostureServer `
        -Method Post `
        -Body $Payload `
        -ContentType "application/json" `
        -Headers $SubmitHeaders `
        -TimeoutSec $PostureServerTimeoutSec

    Write-Host `
        "Submitted OK:" `
        ($Response | ConvertTo-Json -Depth 10)

    $SubmitOk = $true
    $SubmitError = $null
}
catch {
    Write-Host `
        "ERROR submitting to $PostureServer : $($_.Exception.Message)" `
        -ForegroundColor Red

    $SubmitOk = $false
    $SubmitError = $_.Exception.Message
}

# ---------------------------------------------------------------------------
# Machine-readable result for JobWorker
#
# JobWorker parses this stdout line regardless of whether the HTTP POST
# above succeeded. If submitted=true, the assessment already exists in
# Postgres (written by PostureIngestService) and JobWorker just marks the job
# COMPLETE. If submitted=false - the POST itself failed, even though
# collection succeeded - JobWorker writes the fallback assessment row
# itself (status=ERROR, detail=submitError), since the platform's
# "observation must always leave evidence" principle means a check that
# ran but couldn't be recorded is still worth a row, not a silent drop.
#
# JobWorker uses the LAST RESULT_JSON line in stdout, and only
# submitted=true counts as success.
# ---------------------------------------------------------------------------

$FinalResult = [ordered]@{
    jobId               = $JobId
    computer            = $OS.CSName
    mac                 = $Nic.MACAddress
    os                  = $OS.Caption
    osVersion           = $OS.Version
    status              = $OverallStatus
    detail              = $Detail
    submitted           = $SubmitOk
    submitError         = $SubmitError
    checks              = $AllChecks
    appsCount           = $InstalledApps.Count
    appCollectionMethod = $AppCollectionMethod
    appCollectionError  = $AppCollectionError
    listening_ports     = @($Ports)
    installed_apps      = @($InstalledApps)
    hardware            = $HardwareInfo
    resource_usage      = $ResourceUsage
    top_processes       = @($TopProcesses)
    open_ports          = @($OpenPorts)
    blocked_ports       = @($BlockedPortsUnreachable)
}

Write-Output (
    "RESULT_JSON:" +
    ($FinalResult | ConvertTo-Json -Compress -Depth 10)
)

# Return a non-zero process exit code only when the actual posture
# collection failed. A submission failure is already represented by
# submitted=false in RESULT_JSON and is handled by the caller.
if (-not $CollectionSucceeded) {
    exit 1
}

exit 0