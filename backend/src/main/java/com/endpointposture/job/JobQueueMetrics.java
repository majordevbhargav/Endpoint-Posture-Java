package com.endpointposture.job;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Publishes two operational gauges to the Micrometer / Prometheus registry:
 *
 * <ul>
 *   <li>{@code posture_job_queue_depth{status="QUEUED"|"RUNNING"}} — how many
 *       jobs are waiting or currently executing. A persistently growing QUEUED
 *       count means workers are stalled or underpowered.</li>
 *   <li>{@code posture_job_oldest_queued_age_seconds} — wall-clock age of the
 *       oldest eligible QUEUED job. Exceeding the recheck interval here is the
 *       primary saturation signal.</li>
 * </ul>
 *
 * <p>Gauges pull live values from the repository on every scrape (Prometheus
 * default: 15 s), so no background thread or cache is needed.</p>
 *
 * <p>The metrics are visible at {@code /actuator/prometheus}, which is
 * restricted to authenticated ADMIN users (see {@code SecurityConfig}).</p>
 */
@Component
public class JobQueueMetrics {

    public JobQueueMetrics(MeterRegistry registry, PostureJobRepository jobs) {
        Gauge.builder("posture_job_queue_depth", jobs, r -> r.countByStatus(JobStatus.QUEUED))
                .description("Number of jobs currently waiting in the QUEUED state")
                .tag("status", "QUEUED")
                .register(registry);

        Gauge.builder("posture_job_queue_depth", jobs, r -> r.countByStatus(JobStatus.RUNNING))
                .description("Number of jobs currently executing (RUNNING state)")
                .tag("status", "RUNNING")
                .register(registry);

        Gauge.builder("posture_job_oldest_queued_age_seconds", jobs, r -> {
            Instant now = Instant.now();
            return r.findFirstByStatusOrderByCreatedAtAsc(JobStatus.QUEUED)
                    .map(job -> {
                        // A job waiting out retry backoff is not yet eligible; measure
                        // from when it actually becomes claimable, not from creation.
                        Instant eligibleSince = job.getNextAttemptAt() != null
                                ? job.getNextAttemptAt()
                                : job.getCreatedAt();
                        return (double) Math.max(0, Duration.between(eligibleSince, now).getSeconds());
                    })
                    .orElse(0.0); // 0 when the queue is empty
        })
                .description(
                        "Age in seconds of the oldest eligible QUEUED job. " +
                        "0 when the queue is empty. Alert when this exceeds the recheck interval.")
                .register(registry);
    }
}
