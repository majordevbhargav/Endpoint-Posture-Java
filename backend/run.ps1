$base = "http://localhost:8090"
foreach ($v in "ADMIN_USER","ADMIN_PASSWORD","POSTURE_API_KEY") {
    if (-not (Get-Item "env:$v" -ErrorAction SilentlyContinue)) { throw "Set `$env:$v before running this script." }
}
$body = @{ username = $env:ADMIN_USER; password = $env:ADMIN_PASSWORD } | ConvertTo-Json
$login = Invoke-RestMethod -Uri "$base/api/v1/auth/login" -Method Post -ContentType application/json -Body $body
$token = $login.token
$headers = @{ Authorization = "Bearer $token" }
# Pick your endpoint by MAC from an env var instead of hardcoding it
$myEndpoint = $endpoints | Where-Object { $_.macAddress -eq $env:TEST_ENDPOINT_MAC }
# In section 3 use:  $env:POSTURE_API_KEY  (already set above), not a literal