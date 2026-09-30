$base = "http://localhost:8090"
$env:ADMIN_USER = "admin"                 # your seed admin
$env:ADMIN_PASSWORD = "<your SEED_ADMIN_PASSWORD>"
$env:TEST_ENDPOINT_MAC = "<a real MAC from /endpoints>"

function Login($u, $p) {
    (Invoke-RestMethod "$base/api/v1/auth/login" -Method Post -ContentType application/json `
        -Body (@{ username = $u; password = $p } | ConvertTo-Json)).token
}
function H($t) { @{ Authorization = "Bearer $t" } }
function Try-Post($t, $path, $body) {
    try { Invoke-RestMethod "$base$path" -Method Post -Headers (H $t) -ContentType application/json -Body $body | Out-Null; "200 OK" }
    catch { "$([int]$_.Exception.Response.StatusCode) $($_.Exception.Response.StatusCode)" }
}

# 1. Admin creates one user per role (passwords need 12+ characters)
$admin = Login $env:ADMIN_USER $env:ADMIN_PASSWORD
foreach ($r in "VIEWER","ANALYST","OPERATOR") {
    Invoke-RestMethod "$base/api/v1/users" -Method Post -Headers (H $admin) -ContentType application/json `
        -Body (@{ username = "test-$($r.ToLower())"; password = "Test-Password-123!"; role = $r } | ConvertTo-Json) | Out-Null
}

# 2. Find a real endpoint id
$ep = (Invoke-RestMethod "$base/api/v1/endpoints" -Headers (H $admin)) | Where-Object { $_.macAddress -eq $env:TEST_ENDPOINT_MAC }
$body = @{ endpointId = $ep.id } | ConvertTo-Json

# 3. Try the same actions as each role
foreach ($r in "viewer","analyst","operator") {
    $t = Login "test-$r" "Test-Password-123!"
    "--- $r ---"
    "enqueue job : " + (Try-Post $t "/api/v1/jobs" (@{ endpointId = $ep.id; jobType = "POSTURE_CHECK" } | ConvertTo-Json))
    "share       : " + (Try-Post $t "/api/v1/ise/posture/share" $body)
    "restrict    : (skipped on purpose, it really quarantines the device)"
}