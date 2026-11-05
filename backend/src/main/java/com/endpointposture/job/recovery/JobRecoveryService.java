package com.endpointposture.job.recovery;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.job.JobOutcomeService;
import com.endpointposture.job.JobService;
import com.endpointposture.job.JobType;
import com.endpointposture.job.PostureJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Executes atomic, crash-resilient recovery for stale jobs.
 *
 * <p>A single transactional boundary ensures that the job state transition,
 * failure evidence recording, and recovery audit event commit together or roll back entirely.</p>
 */
@Service
public class JobRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(JobRecoveryService.class);

    private final JobService jobService;
    private final JobOutcomeService outcomes;
    private final JobRecoveryEventRepository recoveryEventRepository;

    public JobRecoveryService(
            JobService jobService,
            JobOutcomeService outcomes,
            JobRecoveryEventRepository recoveryEventRepository) {
        this.jobService = jobService;
        this.outcomes = outcomes;
        this.recoveryEventRepository = recoveryEventRepository;
    }

    /**
     * Atomically recovers a stale job attempt.
     *
     * @param job the candidate job observed to be stale
     * @param recoveryType trigger type (LEASE_EXPIRED or PROCESS_TIMEOUT)
     * @param reason failure detail
     * @return true if this recovery successfully transitioned the job, false if the attempt was already superseded
     */
    @Transactional
    public boolean recoverJob(PostureJob job, RecoveryType recoveryType, String reason) {
        if (job == null) {
            return false;
        }

        UUID jobId = job.getId();
        int attempt = job.getAttemptCount();
        String claimedBy = job.getClaimedBy();
        JobType jobType = job.getJobType();

        // 1. Atomically transition job state only if it is still the current RUNNING attempt
        boolean wasRunning = switch (recoveryType) {
            case LEASE_EXPIRED -> jobService.failExpiredLeaseAttempt(jobId, attempt, reason);
            case WORKER_REVOKED -> jobService.failRevokedWorkerAttempt(jobId, attempt, reason);
            case PROCESS_TIMEOUT -> jobService.failAttempt(jobId, attempt, reason);
        };

        if (!wasRunning) {
            log.debug("Job {} attempt {} was already superseded or lease renewed; skipping recovery", jobId, attempt);
            return false;
        }

        Endpoint endpoint = job.getEndpoint();
        UUID endpointId = endpoint != null ? endpoint.getId() : null;

        // 2. Record failure evidence via centralized outcome service
        if (endpointId != null) {
            outcomes.recordFailureEvidence(jobType, endpointId, jobId, reason);
        }

        // 3. Persist the recovery audit event
        JobRecoveryEvent event = JobRecoveryEvent.builder()
                .jobId(jobId)
                .endpointId(endpointId)
                .endpointMacAddress(endpoint != null ? endpoint.getMacAddress() : "unknown")
                .jobType(jobType)
                .attemptCount(attempt)
                .claimedBy(claimedBy)
                .recoveryType(recoveryType)
                .reason(reason)
                .recoveredAt(Instant.now())
                .build();

        recoveryEventRepository.saveAndFlush(event);

        log.info("Recovered stale job {} ({}, attempt {}) via {} for endpoint {}",
                jobId, jobType, attempt, recoveryType, endpoint != null ? endpoint.getMacAddress() : "unknown");

        return true;
    }
}
