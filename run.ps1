# ============================================================
# 0. Setup — run this block first, every new session
# ============================================================
$base = "http://localhost:8090"

$login = Invoke-RestMethod -Uri "$base/api/v1/auth/login" -Method Post -ContentType application/json -Body '{"username":"admin","password":"Login@123"}'
$token = $login.token
$headers = @{ Authorization = "Bearer $token" }
Write-Host "Logged in as $($login.username), role $($login.role)"

# ============================================================
# 1. Auth — confirm login works and bad creds are rejected
# ============================================================
try {
    Invoke-RestMethod -Uri "$base/api/v1/auth/login" -Method Post -ContentType application/json -Body '{"username":"admin","password":"wrong"}'
} catch {
    Write-Host "Expected 401 for bad password: $($_.Exception.Response.StatusCode)"
}

# ============================================================
# 2. Endpoints — read (should list whatever ISE/posture has discovered)
# ============================================================
$endpoints = Invoke-RestMethod -Uri "$base/api/v1/endpoints" -Headers $headers
$endpoints | Format-Table id, macAddress, ipAddress, connected

# Pick your own laptop's endpoint (adjust the MAC filter to match yours)
$myEndpoint = $endpoints | Where-Object { $_.macAddress -eq "10:68:38:80:92:E4" }
$myId = $myEndpoint.id
Write-Host "Using endpoint id: $myId"

Invoke-RestMethod -Uri "$base/api/v1/endpoints/$myId" -Headers $headers

# ============================================================
# 3. Posture ingestion — run the real agent, then read it back
# ============================================================
$env:POSTURE_API_KEY = "dev-local-key-123"
Push-Location scripts
.\posture_agent.ps1
Pop-Location

Invoke-RestMethod -Uri "$base/api/v1/endpoints/$myId/posture/latest" -Headers $headers
Invoke-RestMethod -Uri "$base/api/v1/endpoints/$myId/posture" -Headers $headers   # full history

# ============================================================
# 4. Job queue — enqueue, then watch JobWorker pick it up
# ============================================================
$jobBody = @{ endpointId = $myId; jobType = "POSTURE_CHECK" } | ConvertTo-Json
$job = Invoke-RestMethod -Uri "$base/api/v1/jobs" -Method Post -Headers $headers -ContentType application/json -Body $jobBody
Write-Host "Enqueued job $($job.id), status $($job.status)"

# Poll until it flips to COMPLETE/FAILED (JobWorker ticks every 3s per app.jobs.poll-interval-ms)
do {
    Start-Sleep -Seconds 3
    $jobStatus = Invoke-RestMethod -Uri "$base/api/v1/jobs/endpoint/$myId" -Headers $headers | Select-Object -First 1
    Write-Host "Job status: $($jobStatus.status)"
} while ($jobStatus.status -in @("QUEUED","RUNNING"))

Invoke-RestMethod -Uri "$base/api/v1/jobs" -Headers $headers | Format-Table id, jobType, status, attemptCount, errorMessage

# ============================================================
# 5. Hardware health — run the agent, read it back, test via job too
# ============================================================
Push-Location scripts
.\hardware_health_agent.ps1
Pop-Location

Invoke-RestMethod -Uri "$base/api/v1/endpoints/$myId/hardware-health/latest" -Headers $headers
Invoke-RestMethod -Uri "$base/api/v1/endpoints/$myId/hardware-health" -Headers $headers

$hwJobBody = @{ endpointId = $myId; jobType = "HARDWARE_CHECK" } | ConvertTo-Json
Invoke-RestMethod -Uri "$base/api/v1/jobs" -Method Post -Headers $headers -ContentType application/json -Body $hwJobBody

# ============================================================
# 6. Session watcher — no manual call, just observe
# ============================================================
Write-Host "Check the running mvn console for 'IseSessionWatcher' log lines every 15s — confirms ISE polling + auto-enqueue on reconnect."

# ============================================================
# 7. ISE actions — share / restrict / clear (needs step 3 done first)
# ============================================================
Invoke-RestMethod -Uri "$base/api/v1/ise/posture/share" -Method Post -Headers $headers -ContentType application/json -Body (@{endpointId=$myId} | ConvertTo-Json)

$restrictBody = @{ endpointId = $myId; policy = "Quarantine" } | ConvertTo-Json
Invoke-RestMethod -Uri "$base/api/v1/ise/enforcement/restrict" -Method Post -Headers $headers -ContentType application/json -Body $restrictBody

Invoke-RestMethod -Uri "$base/api/v1/ise/enforcement/clear" -Method Post -Headers $headers -ContentType application/json -Body (@{endpointId=$myId} | ConvertTo-Json)

# ============================================================
# 8. Audit trail — confirm every ISE action above left a row
# ============================================================
Invoke-RestMethod -Uri "$base/api/v1/audit/ise-actions" -Headers $headers | Format-Table actionType, succeeded, detail, occurredAt
Invoke-RestMethod -Uri "$base/api/v1/audit/ise-actions?endpointId=$myId" -Headers $headers

# ============================================================
# 9. How ro run in Swagger 

#Same coverage via Swagger (no scripting needed)
#Open http://localhost:8090/swagger-ui.html
#POST /api/v1/auth/login → Try it out → {"username":"admin","password":"Login@123"} → copy the token field
#Click Authorize (top right) → paste the token (no Bearer prefix needed, Swagger adds it) → Authorize
#Now every endpoint below works with one click each:
#GET /api/v1/endpoints — get a real id from the response, use it everywhere below
#GET /api/v1/endpoints/{id}/posture/latest
#POST /api/v1/jobs — body {"endpointId": "<real-id>", "jobType": "POSTURE_CHECK"}
#GET /api/v1/jobs/endpoint/{endpointId} — watch status change
#GET /api/v1/endpoints/{id}/hardware-health/latest
#POST /api/v1/ise/posture/share — body {"endpointId": "<real-id>"}
#POST /api/v1/ise/enforcement/restrict — body {"endpointId": "<real-id>", "policy": "Quarantine"}
#POST /api/v1/ise/enforcement/clear
#GET /api/v1/audit/ise-actions

# ============================================================