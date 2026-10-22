<# Prompts for each secret and stores it as a MACHINE environment variable (nothing written to git or the service XML).
   Run as Administrator, then restart the service. #>
$ErrorActionPreference = "Stop"
function Ask($n, [switch]$Optional) {
    $s = Read-Host "$n" -AsSecureString
    $p = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s))
    if (-not $p -and -not $Optional) { throw "$n is required" }
    if ($p) { [Environment]::SetEnvironmentVariable($n, $p, "Machine") }
}
$jwt = -join ((48..57 + 65..90 + 97..122) | Get-Random -Count 48 | ForEach-Object { [char]$_ })
[Environment]::SetEnvironmentVariable("JWT_SECRET", $jwt, "Machine")   # generated, 48 chars
Write-Host "JWT_SECRET generated." -ForegroundColor Green
"SEED_ADMIN_PASSWORD","POSTURE_API_KEY","DB_PASSWORD","ISE_PASSWORD","ISE_FEED_PASSWORD" | ForEach-Object { Ask $_ }
$u = Read-Host "ISE_BASE_URL (e.g. https://ise.corp:443)"; [Environment]::SetEnvironmentVariable("ISE_BASE_URL",$u,"Machine")
$u = Read-Host "ISE_USERNAME"; [Environment]::SetEnvironmentVariable("ISE_USERNAME",$u,"Machine")
[Environment]::SetEnvironmentVariable("DB_URL","jdbc:postgresql://localhost:5434/endpoint_posture?tcpKeepAlive=true","Machine")
Write-Host "Done. Restart: Restart-Service EndpointPostureEngine" -ForegroundColor Green
