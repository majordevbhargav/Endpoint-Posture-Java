<# Post-install smoke test. -Base is the PUBLIC https URL (through the proxy). #>
param([string]$Base = "https://posture.corp.example", [string]$FeedUser = "ise-feed")
$ok = $true
function Check($name, [scriptblock]$t) { try { $r = & $t; Write-Host "PASS  $name $r" -ForegroundColor Green } catch { Write-Host "FAIL  $name : $($_.Exception.Message)" -ForegroundColor Red; $script:ok = $false } }
Check "API health"              { (Invoke-RestMethod "$Base/api/../actuator/health" -ErrorAction Stop).status } 
Check "Swagger blocked"         { try { Invoke-WebRequest "$Base/swagger-ui.html" -UseBasicParsing | Out-Null; throw "reachable" } catch { if ($_.Exception.Message -eq "reachable") { throw }; "" } }
Check "Anonymous API is 401/403"{ try { Invoke-WebRequest "$Base/api/v1/endpoints" -UseBasicParsing | Out-Null; throw "open" } catch { if ($_.Exception.Message -eq "open") { throw }; "" } }
Check "Feed rejects bad Basic"  { try { Invoke-WebRequest "$Base/api/v1/integrations/ise/endpoints" -Headers @{Authorization="Basic "+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${FeedUser}:wrong"))} -UseBasicParsing | Out-Null; throw "accepted" } catch { if ($_.Exception.Message -eq "accepted") { throw }; "" } }
Check "TLS cert valid"          { Invoke-WebRequest $Base -UseBasicParsing | Out-Null }
Check "Service running"         { if ((Get-Service EndpointPostureEngine).Status -ne "Running") { throw "not running" } }
Check "Service account"         { (Get-CimInstance Win32_Service -Filter "Name='EndpointPostureEngine'").StartName }
if (-not $ok) { exit 1 }
