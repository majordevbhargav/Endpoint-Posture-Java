# Scale Simulation Guide

This directory contains resources for simulating fleet operations at scale (50,000 endpoints, 20,000 concurrent sessions).

---

## 1. Prerequisites and Setup

### 1.1 Create the Simulation Database
Create a dedicated Postgres database so the development/test database is preserved:
```sql
CREATE DATABASE endpoint_posture_sim WITH OWNER ep_app;
```
Ensure Flyway migrations `V1` through `V22` are applied to `endpoint_posture_sim` (either by starting the application once with `DB_URL=jdbc:postgresql://localhost:5434/endpoint_posture_sim?tcpKeepAlive=true` or via Flyway CLI).

### 1.2 Seed 50,000 Endpoints
Run the seed script against the simulation database:
```powershell
psql -h localhost -p 5434 -U ep_app -d endpoint_posture_sim -f scripts/sim/seed_50k.sql
```
This populates:
- 50,000 simulated Windows endpoints (`02:00:...`, `10.x.x.x`, `SIM-00001` through `SIM-50000`).
- Initial assessments (`COMPLIANT`, `NON_COMPLIANT`, `ERROR`) and firewall check results.
- `latest_assessment_id`, `latest_status`, and `latest_assessed_at` pointers on `endpoint`.

---

## 2. Running the Simulation

Execute the simulation startup script in the `backend` folder:
```powershell
cd backend
.\start-sim.ps1
```

This runs the backend with:
- Profile: `sim` (enables `SimulatedIseSessionClient`, `SimReporter`, and synthetic worker loop).
- JVM options: `-Xlog:gc*:file=gc-sim.log:time,uptime -Xmx2g` (logs GC activity and caps heap at 2 GB).

---

## 3. Interpreting `SIM` Log Lines

Every 60 seconds (configured by `app.sim.report-interval-ms`), `SimReporter` outputs metrics across four lines:

### 3.1 Sessions and Job Throughput
```text
sessions=20000 watcherTickMs=850 (timerMs=820) | queued=120 running=40 complete=2500 failed=10 throughput=118/min
```
- **`sessions`**: Active ISE sessions simulated (ramping up from 0 to 20,000 over 20 polls).
- **`watcherTickMs`**: Estimated tick duration based on call intervals from the mock client.
- **`timerMs`**: Real measured duration recorded by the Micrometer Timer `ise_watcher_tick_seconds` inside `IseSessionWatcher.tick()`.
- **`queued`**: Jobs waiting in `QUEUED` state.
- **`running`**: Jobs currently executing across worker threads.
- **`complete`**: Cumulative completed jobs count.
- **`failed`**: Cumulative failed jobs count.
- **`throughput`**: Rate of completed jobs per minute over the last measurement interval.

### 3.2 Queue Depth by Type and Status
```text
queue depth by type/status: [{job_type=POSTURE_CHECK, status=QUEUED, cnt=80}, {job_type=HARDWARE_CHECK, status=QUEUED, cnt=40}, ...]
```
Shows how many jobs are pending or executing per job type, ensuring neither posture nor hardware sweeps starve the queue.

### 3.3 Database Size and Row Counts
```text
db=482 MB rows: endpoint=50000 assessment=52500 check_result=52500 hardware_health=2500 inventory=0 job=5320
```
- Shows total on-disk PostgreSQL database size (`pg_size_pretty(pg_database_size())`).
- Row counts for `endpoint`, `assessment`, `check_result`, `hardware_health`, `endpoint_inventory`, and `posture_job`.

### 3.4 Read Query Latencies
```text
latency ms: dashboardSummary=35 dashboardTrend7=28 endpointsPage25=42 endpointsSearch=65
```
- **`dashboardSummary`**: Wall-clock time to calculate Command Center overview aggregates.
- **`dashboardTrend7`**: Wall-clock time to compute 7-day posture trend from daily rollups.
- **`endpointsPage25`**: Paged endpoint list latency (first 25 records with latest posture status).
- **`endpointsSearch`**: Free-text search latency targeting trigram indexes (`V22__trgm_search_indexes.sql`).

---

## 4. Benchmark Comparison Targets

### 4.1 Measured Baseline (Pre-S12 at 20,000 Sessions)
- `watcherTickMs`: 0.5 – 3.0 s upper bound at 20,000 sessions.
- `dashboardSummary`: 20 – 60 ms.
- `dashboardTrend7`: 20 – 60 ms.
- `endpointsPage25`: 25 – 500 ms.
- `throughput`: ~115 jobs/min (40 workers × ~3 jobs/min).
- Hikari Stalls: Two stalls were observed at 11:42 and 13:00 (housekeeper delta 1m39s / 1m6s) before socket keepalive and connection timeout tuning.

### 4.2 Post-S12 Target Criteria
- **Zero Hikari connection drops/stalls**: `keepalive-time: 120000`, `max-lifetime: 900000`, `connection-timeout: 15000`, and `tcpKeepAlive=true` prevent hung pooled connections.
- **Reduced tick I/O**: `touch-min-minutes: 5` suppresses 80% of `endpoint.last_seen_at` updates.
- **Bounded sweeps**: `max-per-sweep: 500` ensures periodic rechecks do not monopolize DB transactions or flood the queue.
- **Fast text search**: Trigram indexes ensure `endpointsSearch` remains < 150 ms even on 50,000 endpoints.
