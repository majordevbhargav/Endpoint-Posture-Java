<# Daily pg_dump from the docker container, 14-day retention. Schedule with Task Scheduler.
   Evidence is kept 90 days in the DB, so backups are the only long-term copy. #>
param([string]$Dir = "D:\PostureBackups", [string]$Container = "ep-postgres", [int]$KeepDays = 14)
$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $Dir | Out-Null
$file = Join-Path $Dir ("endpoint_posture_{0:yyyyMMdd_HHmm}.dump" -f (Get-Date))
docker exec $Container pg_dump -U ep_app -Fc endpoint_posture > $file
if ((Get-Item $file).Length -lt 10KB) { throw "Backup suspiciously small: $file" }
Get-ChildItem $Dir -Filter *.dump | Where-Object LastWriteTime -lt (Get-Date).AddDays(-$KeepDays) | Remove-Item
Write-Host "Backup OK: $file"
# RESTORE TEST (do it once, into a scratch DB):
#   docker exec -i ep-postgres createdb -U ep_app restore_test
#   Get-Content $file -Raw -Encoding Byte | docker exec -i ep-postgres pg_restore -U ep_app -d restore_test
