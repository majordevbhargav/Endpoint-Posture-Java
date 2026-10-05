package com.endpointposture.sim;

import com.endpointposture.dashboard.DashboardService;
import com.endpointposture.endpoint.EndpointService;
import com.endpointposture.job.JobStatus;
import com.endpointposture.job.PostureJobRepository;
import com.endpointposture.posture.AssessmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Logs one measurement line per interval during a simulation, so the results can be
 * copied straight into {@code ROADMAP.md}. Measures: queue state and throughput, the
 * watcher's tick time, database size and row counts, and how long the dashboard
 * summary, trend and the full-list endpoints take at the current data volume.
 */
@Component
@Profile("sim")
public class SimReporter {

    private static final Logger log = LoggerFactory.getLogger("SIM");

    private final PostureJobRepository jobs;
    private final JdbcTemplate jdbc;
    private final DashboardService dashboard;
    private final EndpointService endpoints;
    private final AssessmentService assessments;
    private final SimulatedIseSessionClient iseClient;
    private long lastComplete = 0;
    private long lastAt = System.currentTimeMillis();

    public SimReporter(PostureJobRepository jobs, JdbcTemplate jdbc, DashboardService dashboard,
                       EndpointService endpoints, AssessmentService assessments,
                       SimulatedIseSessionClient iseClient) {
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.dashboard = dashboard;
        this.endpoints = endpoints;
        this.assessments = assessments;
        this.iseClient = iseClient;
    }

    @Scheduled(fixedDelayString = "${app.sim.report-interval-ms:60000}", initialDelay = 30000)
    public void report() {
        try {
            long complete = jobs.countByStatus(JobStatus.COMPLETE);
            long now = System.currentTimeMillis();
            double perMin = (complete - lastComplete) * 60000.0 / Math.max(1, now - lastAt);
            lastComplete = complete;
            lastAt = now;

            log.info("sessions={} watcherTickMs={} | queued={} running={} complete={} failed={} throughput={}/min",
                    iseClient.currentSessionCount(), iseClient.lastTickMillis(),
                    jobs.countByStatus(JobStatus.QUEUED), jobs.countByStatus(JobStatus.RUNNING),
                    complete, jobs.countByStatus(JobStatus.FAILED), Math.round(perMin));

            log.info("db={} rows: endpoint={} assessment={} check_result={} inventory={} job={}",
                    jdbc.queryForObject("SELECT pg_size_pretty(pg_database_size(current_database()))", String.class),
                    count("endpoint"), count("assessment"), count("check_result"),
                    count("endpoint_inventory"), count("posture_job"));

            log.info("latency ms: dashboardSummary={} dashboardTrend7={} listEndpoints={} latestPostureAll={}",
                    time(dashboard::summary), time(() -> dashboard.trend(7)),
                    time(endpoints::listAll), time(assessments::getLatestForAllEndpoints));
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