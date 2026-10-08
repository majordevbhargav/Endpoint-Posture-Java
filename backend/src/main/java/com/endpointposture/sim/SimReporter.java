package com.endpointposture.sim;

import com.endpointposture.dashboard.DashboardService;
import com.endpointposture.endpoint.EndpointQueryService;
import com.endpointposture.job.JobStatus;
import com.endpointposture.job.PostureJobRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Logs one measurement line per interval during a simulation, so the results can be
 * copied straight into {@code ROADMAP.md}. Measures: queue state and throughput, the
 * watcher's tick time, database size and row counts, and how long the dashboard
 * summary, trend and the page query take at the current data volume.
 */
@Component
@Profile("sim")
public class SimReporter {

    private static final Logger log = LoggerFactory.getLogger("SIM");

    private final PostureJobRepository jobs;
    private final JdbcTemplate jdbc;
    private final DashboardService dashboard;
    private final EndpointQueryService query;
    private final SimulatedIseSessionClient iseClient;
    private final MeterRegistry registry;
    private long lastComplete = 0;
    private long lastAt = System.currentTimeMillis();

    public SimReporter(PostureJobRepository jobs, JdbcTemplate jdbc, DashboardService dashboard,
                       EndpointQueryService query, SimulatedIseSessionClient iseClient,
                       MeterRegistry registry) {
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.dashboard = dashboard;
        this.query = query;
        this.iseClient = iseClient;
        this.registry = registry;
    }

    @Scheduled(fixedDelayString = "${app.sim.report-interval-ms:60000}", initialDelay = 30000)
    public void report() {
        try {
            long complete = jobs.countByStatus(JobStatus.COMPLETE);
            long now = System.currentTimeMillis();
            double perMin = (complete - lastComplete) * 60000.0 / Math.max(1, now - lastAt);
            lastComplete = complete;
            lastAt = now;

            Timer watcherTimer = registry.find("ise_watcher_tick_seconds").timer();
            double watcherTickSeconds = watcherTimer != null ? watcherTimer.mean(TimeUnit.SECONDS) : -1.0;
            double watcherTickMs = watcherTickSeconds >= 0 ? watcherTickSeconds * 1000.0 : iseClient.lastTickMillis();

            log.info("sessions={} watcherTickMs={} (timerMs={}) | queued={} running={} complete={} failed={} throughput={}/min",
                    iseClient.currentSessionCount(), iseClient.lastTickMillis(), Math.round(watcherTickMs),
                    jobs.countByStatus(JobStatus.QUEUED), jobs.countByStatus(JobStatus.RUNNING),
                    complete, jobs.countByStatus(JobStatus.FAILED), Math.round(perMin));

            List<Map<String, Object>> queueDepth = jdbc.queryForList(
                    "SELECT job_type, status, count(*) AS cnt FROM posture_job GROUP BY job_type, status ORDER BY job_type, status");
            log.info("queue depth by type/status: {}", queueDepth);

            log.info("db={} rows: endpoint={} assessment={} check_result={} hardware_health={} inventory={} job={}",
                    jdbc.queryForObject("SELECT pg_size_pretty(pg_database_size(current_database()))", String.class),
                    count("endpoint"), count("assessment"), count("check_result"),
                    count("hardware_health"), count("endpoint_inventory"), count("posture_job"));

            log.info("latency ms: dashboardSummary={} dashboardTrend7={} endpointsPage25={} endpointsSearch={}",
                    time(dashboard::summary), time(() -> dashboard.trend(7)),
                    time(() -> query.page(0, 25, null, true, null)), time(() -> query.page(0, 5, "SIM-0123", null, null)));
        } catch (Exception e) {
            log.warn("Report failed: {}", e.getMessage());
        }
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return n == null ? 0 : n;
    }

    private long time(Supplier<?> call) {
        long t = System.nanoTime();
        call.get();
        return (System.nanoTime() - t) / 1_000_000;
    }
}