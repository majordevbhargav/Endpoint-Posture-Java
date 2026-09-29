# Manual smoke test. Set these first:
#   $env:ADMIN_USER, $env:ADMIN_PASSWORD, $env:TEST_ENDPOINT_MAC
$base = "http://localhost:8090"
foreach ($v in "ADMIN_USER", "ADMIN_PASSWORD", "TEST_ENDPOINT_MAC") {
    if (-not (Get-Item "env:$v" -ErrorAction SilentlyContinue)) { throw "Set `$env:$v before running this script." }
}

$body  = @{ username = $env:ADMIN_USER; password = $env:ADMIN_PASSWORD } | ConvertTo-Json
$login = Invoke-RestMethod -Uri "$base/api/v1/auth/login" -Method Post -ContentType application/json -Body $body
$headers = @{ Authorization = "Bearer $($login.token)" }

$endpoints  = Invoke-RestMethod -Uri "$base/api/v1/endpoints" -Headers $headers
$myEndpoint = $endpoints | Where-Object { $_.macAddress -eq $env:TEST_ENDPOINT_MAC }
if (-not $myEndpoint) { throw "No endpoint with MAC $($env:TEST_ENDPOINT_MAC)" }

$job = Invoke-RestMethod -Uri "$base/api/v1/jobs" -Method Post -Headers $headers -ContentType application/json `
    -Body (@{ endpointId = $myEndpoint.id; jobType = "POSTURE_CHECK" } | ConvertTo-Json)
Write-Host "Enqueued job $($job.id) for $($myEndpoint.macAddress)"