<#
.SYNOPSIS
    One-time setup: stores a single admin credential, encrypted, for
    posture_agent.ps1 to use automatically instead of prompting every
    time you check a device.

.DESCRIPTION
    Uses Windows DPAPI (Export-Clixml under the hood) - the resulting
    file can only be decrypted by YOUR Windows account, on THIS machine.
    Copying it to another machine, or another user account on this one,
    produces an unreadable file, not a security hole. Nobody, including
    us in this chat, ever sees the actual password - it goes straight
    from the secure prompt below into the encrypted file.

.PARAMETER Path
    Where to save the encrypted credential. Defaults to
    posture_common_cred.xml next to this script, which is where
    posture_agent.ps1 and hardware_health_agent.ps1 look for it.

.NOTES
    Run this as the SAME Windows account that runs the Spring Boot backend.
    JobWorker starts powershell.exe as that account, and DPAPI only
    decrypts a file for the account that saved it. A mismatch shows up
    later as "Could not decrypt the stored credential" in an ERROR
    assessment.

    The resulting .xml file stays out of git (see .gitignore).

.EXAMPLE
    .\Save-PostureCredential.ps1
    (prompts once, saves to posture_common_cred.xml next to this script)
#>

param(
    [string]$Path = "$PSScriptRoot\posture_common_cred.xml"
)

Write-Host "This stores ONE credential, used automatically for every device" -ForegroundColor Cyan
Write-Host "you check - unless you explicitly override it per-device later." -ForegroundColor Cyan
Write-Host ""

$cred = Get-Credential -Message "Common admin credential for posture checks (e.g. .\Administrator, or a domain service account like CORP\svc-posture)"

if (-not $cred) {
    Write-Host "Cancelled - nothing saved." -ForegroundColor Yellow
    exit 0
}

# posture_agent.ps1 re-qualifies a handful of known wrong-scope patterns
# automatically (no prefix, ".\", this machine's own name), but it can
# only reliably do that when it's UNAMBIGUOUS that the prefix is wrong.
# A prefix that's a specific device's IP address (e.g. "10.66.1.11") is
# ambiguous by design - it might genuinely be what you meant. Catching
# it here, once, at save time, is more reliable than guessing at use
# time against every future device.
$EnteredUser = $cred.UserName
if ($EnteredUser -match '^(\d{1,3}(\.\d{1,3}){3})\\') {
    Write-Host ""
    Write-Host "WARNING: you entered '$EnteredUser' - that's scoped to ONE specific device ($($Matches[1])), not a general credential." -ForegroundColor Yellow
    Write-Host "This credential is meant to be used against MANY devices automatically. If you continue, it will only work against $($Matches[1]) and will silently fail Access Denied on every other device." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "For a credential that works everywhere, use one of:" -ForegroundColor Yellow
    Write-Host "  .\Administrator          (local account, same name on every target)" -ForegroundColor Yellow
    Write-Host "  CORP\svc-posture         (a domain service account)" -ForegroundColor Yellow
    Write-Host ""
    $Continue = Read-Host "Save it scoped to $($Matches[1]) anyway? (Y/N)"
    if ($Continue -notmatch '^[Yy]') {
        Write-Host "Cancelled - nothing saved. Re-run and use .\Administrator or DOMAIN\user instead." -ForegroundColor Yellow
        exit 0
    }
}

$cred | Export-Clixml -Path $Path

Write-Host ""
Write-Host "Saved to: $Path" -ForegroundColor Green
Write-Host "posture_agent.ps1 will now use this automatically." -ForegroundColor Green
Write-Host "Re-run this script any time to change it." -ForegroundColor DarkGray