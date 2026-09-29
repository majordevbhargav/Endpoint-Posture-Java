package com.endpointposture.job;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.hardware.HardwareHealthService;
import com.endpointposture.hardware.config.HardwareAgentProperties;
import com.endpointposture.posture.AssessmentService;
import com.endpointposture.posture.config.PostureAgentProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Recovers jobs stuck in {@code RUNNING} because the backend died or was
 * restarted mid-job. Without this, such a job stays {@code RUNNING}
 * forever: {@link JobWorker} only moves a job out of {@code RUNNING} when
 * the process it launched actually returns, and a dead JVM can't do that -
 * and a job stuck in {@code RUNNING} also silently blocks
 * {@link JobService#enqueueIfDue} from ever queuing a fresh check for that
 * endpoint again.
 *
 * <p>A candidate is considered genuinely stale once it has been
 * {@code RUNNING} longer than its own job type's outer process timeout,
 * plus a safety margin ({@code app.jobs.stale-margin-seconds}), so a job
 * that is merely running long (not stuck) is never recovered out from
 * under a worker that is still legitimately processing it.</p>
 *
 * <p>Recovery goes through {@link JobService#markFailedIfRunning}, which
 * re-checks the job's status itself before touching it - so a job that
 * {@link JobWorker} finished in the narrow gap between this sweep's query
 * and this method's action is left alone, never double-processed and
 * never given a false failure row. Only once that check confirms the job
 * was genuinely still stuck does this class write the same permanent
 * failure evidence {@link JobWorker#fail} writes (an {@code ERROR}
 * assessment, or a {@code succeeded=false} hardware-health row) - a
 * recovered job is never silently different from any other kind of
 * failure in the evidence trail.</p>
 */
@Component
public class StaleJobRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(StaleJobRecoveryScheduler.class);

    private final PostureJobRepository jobRepository;
    private final JobService jobService;
    private final AssessmentService assessmentService;
    private final HardwareHealthService hardwareHealthService;
    private final PostureAgentProperties postureProps;
    private final HardwareAgentProperties hardwareProps;
    private final long marginSeconds;

    public StaleJobRecoveryScheduler(PostureJobRepository jobRepository,
                                      JobService jobService,
                                      AssessmentService assessmentService,
                                      HardwareHealthService hardwareHealthService,
                                      PostureAgentProperties postureProps,
                                      HardwareAgentProperties hardwareProps,
                                      @Value("${app.jobs.stale-margin-seconds:60}") long marginSeconds) {
        this.jobRepository = jobRepository;
        this.jobService = jobService;
        this.assessmentService = assessmentService;
        this.hardwareHealthService = hardwareHealthService;
        this.postureProps = postureProps;
        this.hardwareProps = hardwareProps;
        this.marginSeconds = marginSeconds;
    }

    /** Runs once shortly after startup, in case the backend itself crashed mid-job last time. */
    @PostConstruct
    public void recoverOnStartup() {
        sweep();
    }

    @Scheduled(fixedDelayString = "${app.jobs.stale-sweep-interval-ms:60000}")
    public void sweep() {
        try {
            // Widest possible window at the DB level (the larger of the two
            // per-type timeouts), so nothing stale is missed by the query;
            // each candidate is then re-checked against its own job type's
            // actual, shorter-or-equal timeout before being recovered.
            long widestTimeoutSeconds = Math.max(
                    postureProps.getProcessTimeoutSeconds(), hardwareProps.getProcessTimeoutSeconds());
            Instant widestCutoff = Instant.now().minusSeconds(widestTimeoutSeconds + marginSeconds);

            List<PostureJob> candidates = jobRepository.findStaleRunning(widestCutoff);
            if (candidates.isEmpty()) return;

            int recovered = 0;
            for (PostureJob job : candidates) {
                if (isActuallyStale(job) && recover(job)) {
                    recovered++;
                }
            }
            if (recovered > 0) {
                log.warn("Stale-job sweep recovered {} job(s) stuck in RUNNING", recovered);
            }
        } catch (Exception e) {
            log.error("Stale-job recovery sweep failed (will retry next interval)", e); // never kill the scheduler
        }
    }

    private boolean isActuallyStale(PostureJob job) {
        long timeoutSeconds = job.getJobType() == JobType.POSTURE_CHECK
                ? postureProps.getProcessTimeoutSeconds()
                : hardwareProps.getProcessTimeoutSeconds();
        Instant cutoff = Instant.now().minusSeconds(timeoutSeconds + marginSeconds);
        return job.getStartedAt() != null && job.getStartedAt().isBefore(cutoff);
    }

    /** @return true if the job was genuinely still RUNNING and has now been recovered */
    private boolean recover(PostureJob job) {
        String reason = "Recovered: worker died or the backend restarted mid-job (was RUNNING since "
                + job.getStartedAt() + ")";

        // Atomic check-and-fail first: if JobWorker completed this job in the
        // gap between the query above and this call, this returns false and
        // nothing further happens - no false failure evidence is written.
        boolean wasRunning = jobService.markFailedIfRunning(job.getId(), reason);
        if (!wasRunning) {
            return false;
        }

        Endpoint endpoint = job.getEndpoint();
        switch (job.getJobType()) {
            case POSTURE_CHECK -> assessmentService.recordFailure(endpoint.getId(), job.getId(), reason);
            case HARDWARE_CHECK -> hardwareHealthService.recordFailure(endpoint.getId(), job.getId(), reason);
        }

        log.info("Recovered stale job {} ({}) for endpoint {}",
                job.getId(), job.getJobType(), endpoint.getMacAddress());
        return true;
    }
}