package com.endpointposture.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Timed rechecks as two set-based statements (one per job type) instead of several
 * queries per device. For every CONNECTED endpoint with an IP or hostname it queues an
 * automatic (priority 0) job unless: one of that type is QUEUED/RUNNING, the last
 * COMPLETE one finished within the interval, or the newest job of that type FAILED within
 * the backoff. Hardware additionally needs at least one non-ERROR assessment.
 *
 * <p>Disconnected endpoints are never queued. Only enqueues; never calls Cisco ISE.</p>
 */
@Component
@ConditionalOnProperty(name = "app.jobs.recheck.enabled", havingValue = "true", matchIfMissing = true)
public class RecheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecheckScheduler.class);

    /** %1$s = job type (an enum name, never user input), %2$s = extra condition. */
    private static final String ENQUEUE_SQL = """
            INSERT INTO posture_job (endpoint_id, job_type, status, priority, attempt_count, max_attempts)
            SELECT e.id, '%1$s', 'QUEUED', 0, 0, 3
              FROM endpoint e
             WHERE e.connected
               AND (COALESCE(e.ip_address, '') <> '' OR COALESCE(e.hostname, '') <> '')
               %2$s
               AND NOT EXISTS (SELECT 1 FROM posture_job j
                                WHERE j.endpoint_id = e.id AND j.job_type = '%1$s'
                                  AND j.status IN ('QUEUED', 'RUNNING'))
               AND NOT EXISTS (SELECT 1 FROM posture_job j
                                WHERE j.endpoint_id = e.id AND j.job_type = '%1$s'
                                  AND j.status = 'COMPLETE'
                                  AND j.completed_at > now() - (?::bigint * interval '1 second'))
               AND NOT EXISTS (SELECT 1 FROM posture_job j
                                WHERE j.endpoint_id = e.id AND j.job_type = '%1$s'
                                  AND j.status = 'FAILED'
                                  AND j.completed_at > now() - (?::bigint * interval '1 second')
                                  AND j.created_at = (SELECT max(m.created_at) FROM posture_job m
                                                       WHERE m.endpoint_id = e.id AND m.job_type = '%1$s'))
            """;

    private static final String HARDWARE_ONLY = """
            AND EXISTS (SELECT 1 FROM assessment a WHERE a.endpoint_id = e.id AND a.status <> 'ERROR')
            """;

    private final JdbcTemplate jdbc;
    private final long postureSeconds;
    private final long hardwareSeconds;
    private final long backoffSeconds;

    public RecheckScheduler(JdbcTemplate jdbc,
                            @Value("${app.jobs.recheck.posture-hours:4}") long postureHours,
                            @Value("${app.jobs.recheck.hardware-hours:24}") long hardwareHours,
                            @Value("${app.jobs.recheck.failure-backoff-hours:6}") long failureBackoffHours) {
        this.jdbc = jdbc;
        this.postureSeconds = Duration.ofHours(postureHours).toSeconds();
        this.hardwareSeconds = Duration.ofHours(hardwareHours).toSeconds();
        this.backoffSeconds = Duration.ofHours(failureBackoffHours).toSeconds();
    }

    @Scheduled(
            fixedDelayString = "${app.jobs.recheck.sweep-interval-ms:300000}",
            initialDelayString = "${app.jobs.recheck.sweep-interval-ms:300000}")
    public void sweep() {
        try {
            long started = System.nanoTime();
            int posture = jdbc.update(ENQUEUE_SQL.formatted(JobType.POSTURE_CHECK.name(), ""),
                    postureSeconds, backoffSeconds);
            int hardware = jdbc.update(ENQUEUE_SQL.formatted(JobType.HARDWARE_CHECK.name(), HARDWARE_ONLY),
                    hardwareSeconds, backoffSeconds);

            if (posture > 0 || hardware > 0) {
                log.info("Recheck sweep queued {} posture and {} hardware job(s) in {} ms",
                        posture, hardware, (System.nanoTime() - started) / 1_000_000);
            }
        } catch (Exception e) {
            log.error("Recheck sweep failed (will retry next interval)", e); // never kill the scheduler
        }
    }
}