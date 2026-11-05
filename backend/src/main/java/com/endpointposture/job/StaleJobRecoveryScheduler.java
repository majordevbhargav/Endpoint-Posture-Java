package com.endpointposture.job;

import com.endpointposture.diagnostic.config.DiagnosticAgentProperties;
import com.endpointposture.hardware.config.HardwareAgentProperties;
import com.endpointposture.indicator.config.SecurityIndicatorAgentProperties;
import com.endpointposture.job.recovery.JobRecoveryService;
import com.endpointposture.job.recovery.RecoveryType;
import com.endpointposture.posture.config.PostureAgentProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Recovers jobs stuck in RUNNING because a remote worker lease expired, a worker died,
 * or the backend restarted mid-job.
 */
@Component
public class StaleJobRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(StaleJobRecoveryScheduler.class);

    private final PostureJobRepository jobRepository;
    private final JobRecoveryService recoveryService;
    private final PostureAgentProperties postureProps;
    private final HardwareAgentProperties hardwareProps;
    private final DiagnosticAgentProperties diagnosticProps;
    private final SecurityIndicatorAgentProperties securityProps;
    private final long marginSeconds;

    public StaleJobRecoveryScheduler(
            PostureJobRepository jobRepository,
            JobRecoveryService recoveryService,
            PostureAgentProperties postureProps,
            HardwareAgentProperties hardwareProps,
            DiagnosticAgentProperties diagnosticProps,
            SecurityIndicatorAgentProperties securityProps,
            @Value("${app.jobs.stale-margin-seconds:60}") long marginSeconds) {

        this.jobRepository = jobRepository;
        this.recoveryService = recoveryService;
        this.postureProps = postureProps;
        this.hardwareProps = hardwareProps;
        this.diagnosticProps = diagnosticProps;
        this.securityProps = securityProps;
        this.marginSeconds = marginSeconds;
    }

    @PostConstruct
    public void recoverOnStartup() {
        sweep();
    }

    @Scheduled(fixedDelayString = "${app.jobs.stale-sweep-interval-ms:60000}")
    public void sweep() {
        try {
            long widestTimeoutSeconds = Math.max(
                    Math.max(
                            Math.max(
                                    postureProps.getProcessTimeoutSeconds(),
                                    hardwareProps.getProcessTimeoutSeconds()),
                            diagnosticProps.getProcessTimeoutSeconds()),
                    securityProps.getProcessTimeoutSeconds());

            Instant now = Instant.now();
            Instant widestCutoff = now.minusSeconds(widestTimeoutSeconds + marginSeconds);

            Map<java.util.UUID, PostureJob> candidates = new LinkedHashMap<>();
            for (PostureJob job : jobRepository.findStaleRunning(widestCutoff, now)) {
                candidates.put(job.getId(), job);
            }
            // Revocation takes precedence if a job is also lease-expired.
            List<PostureJob> revokedJobs = jobRepository.findRunningJobsOwnedByRevokedWorkers();
            Set<java.util.UUID> revokedIds = revokedJobs.stream()
                    .map(PostureJob::getId)
                    .collect(Collectors.toSet());
            for (PostureJob job : revokedJobs) {
                candidates.put(job.getId(), job);
            }

            if (candidates.isEmpty()) {
                return;
            }

            int recovered = 0;
            for (PostureJob job : candidates.values()) {
                try {
                    boolean revoked = revokedIds.contains(job.getId());
                    if (revoked || isActuallyStale(job)) {
                        RecoveryType type = revoked ? RecoveryType.WORKER_REVOKED
                                : job.getLeaseExpiresAt() != null ? RecoveryType.LEASE_EXPIRED
                                : RecoveryType.PROCESS_TIMEOUT;
                        String reason = buildRecoveryReason(job, type);
                        if (recoveryService.recoverJob(job, type, reason)) {
                            recovered++;
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to recover stale job {} (endpoint {})",
                            job.getId(),
                            job.getEndpoint() != null ? job.getEndpoint().getMacAddress() : "unknown",
                            e);
                }
            }

            if (recovered > 0) {
                log.warn("Stale-job sweep recovered {} job(s) stuck in RUNNING", recovered);
            }
        } catch (Exception e) {
            log.error("Stale-job recovery sweep failed (will retry next interval)", e);
        }
    }

    private String buildRecoveryReason(PostureJob job, RecoveryType type) {
        if (type == RecoveryType.WORKER_REVOKED) {
            return "Recovered: owning worker was administratively revoked (" + job.getClaimedBy() + ")";
        }
        if (type == RecoveryType.LEASE_EXPIRED) {
            return "Recovered: remote worker lease expired at " + job.getLeaseExpiresAt()
                    + " (claimed by " + (job.getClaimedBy() != null ? job.getClaimedBy() : "unknown") + ")";
        }
        return "Recovered: worker died or the backend restarted mid-job (was RUNNING since "
                + job.getStartedAt() + ")";
    }

    private long timeoutSecondsFor(PostureJob job) {
        return switch (job.getJobType()) {
            case POSTURE_CHECK -> postureProps.getProcessTimeoutSeconds();
            case HARDWARE_CHECK -> hardwareProps.getProcessTimeoutSeconds();
            case DIAGNOSTIC_CHECK -> diagnosticProps.getProcessTimeoutSeconds();
            case SECURITY_CHECK -> securityProps.getProcessTimeoutSeconds();
        };
    }

    private boolean isActuallyStale(PostureJob job) {
        // A remote-leased job is owned by its lease, not by started_at: while the
        // worker keeps renewing, the process-timeout rule must not recover it.
        if (job.getLeaseExpiresAt() != null) {
            return !job.getLeaseExpiresAt().isAfter(Instant.now());
        }
        Instant cutoff = Instant.now().minusSeconds(
                timeoutSecondsFor(job) + marginSeconds);

        return job.getStartedAt() != null
                && job.getStartedAt().isBefore(cutoff);
    }
}
