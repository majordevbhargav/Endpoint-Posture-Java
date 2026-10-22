<# Installs the backend as a Windows service under the dedicated account. Run as Administrator.
   Expects in $Root: WinSW.exe (renamed EndpointPostureEngine.exe), endpoint-posture.xml,
   backend\endpoint-posture-java-0.1.0-SNAPSHOT.jar, scripts\*.ps1  #>
param([string]$Root = "C:\PostureEngine", [string]$Account = "svc-posture")
$ErrorActionPreference = "Stop"
$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) { throw "JDK 21 not found on PATH" }
if ((& java -version 2>&1 | Select-Object -First 1) -notmatch '"21') { Write-Warning "Java is not 21: $(& java -version 2>&1 | Select-Object -First 1)" }
foreach ($f in "EndpointPostureEngine.exe","endpoint-posture.xml","backend\endpoint-posture-java-0.1.0-SNAPSHOT.jar","scripts\posture_agent.ps1","scripts\posture_common_cred.xml") {
    if (-not (Test-Path (Join-Path $Root $f))) { throw "Missing $Root\$f (credential file must be created as $Account first)" }
}
foreach ($v in "JWT_SECRET","SEED_ADMIN_PASSWORD","POSTURE_API_KEY","DB_PASSWORD") {
    if (-not [Environment]::GetEnvironmentVariable($v,"Machine")) { throw "Machine env var $v not set (run Set-PostureSecrets.ps1)" }
}
Push-Location $Root
.\EndpointPostureEngine.exe install
$pwd = Read-Host "Password for $Account" -AsSecureString
$plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($pwd))
sc.exe config EndpointPostureEngine obj= ".\$Account" password= $plain | Out-Null
.\EndpointPostureEngine.exe start
Pop-Location
Start-Sleep 25
Write-Host (Invoke-RestMethod http://localhost:8090/actuator/health | ConvertTo-Json -Compress)
