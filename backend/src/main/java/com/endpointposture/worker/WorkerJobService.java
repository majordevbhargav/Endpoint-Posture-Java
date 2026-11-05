package com.endpointposture.worker;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.job.JobOutcomeService;
import com.endpointposture.job.JobService;
import com.endpointposture.job.JobSpecFactory;
import com.endpointposture.job.JobType;
import com.endpointposture.job.PostureJob;
import com.endpointposture.job.PostureJobRepository;
import com.endpointposture.job.spec.JobSpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class WorkerJobService {
    public record Claimed(UUID jobId, int attempt, JobType jobType, JobSpec spec, Instant leaseExpiresAt) {}
    private final JobService jobService;
    private final JobSpecFactory specFactory;
    private final PostureJobRepository jobs;
    private final JobOutcomeService outcomes;
    private final Duration lease;
    private final Duration maxExtension;

    public WorkerJobService(JobService jobService, JobSpecFactory specFactory, PostureJobRepository jobs,
            JobOutcomeService outcomes,
            @Value("${app.workers.lease-seconds:300}") long leaseSeconds,
            @Value("${app.workers.max-lease-extension-seconds:900}") long maxExtensionSeconds) {
        this.jobService = jobService;
        this.specFactory = specFactory;
        this.jobs = jobs;
        this.outcomes = outcomes;
        this.lease = Duration.ofSeconds(Math.max(30, leaseSeconds));
        this.maxExtension = Duration.ofSeconds(Math.max(this.lease.toSeconds(), maxExtensionSeconds));
    }

    public Optional<Claimed> claim(WorkerPrincipal worker) {
        Optional<PostureJob> claimed = jobService.claimNextJobInShard(worker.claimId(), worker.shard(), lease);
        if (claimed.isEmpty()) return Optional.empty();
        PostureJob job = claimed.get();
        Endpoint endpoint = job.getEndpoint();
        try {
            JobSpec spec = specFactory.build(job, endpoint);
            return Optional.of(new Claimed(job.getId(), job.getAttemptCount(), job.getJobType(), spec, job.getLeaseExpiresAt()));
        } catch (RuntimeException e) {
            failWithEvidence(worker, job.getId(), job.getAttemptCount(), "Could not build job spec: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Renews the lease for a job this worker owns. A null extension means the
     * configured lease length.
     *
     * @throws IllegalArgumentException if the extension is not positive or exceeds the maximum
     * @return the new expiry, or empty when stale, not owned or already expired
     */
    public Optional<Instant> renew(WorkerPrincipal worker, UUID jobId, int attempt, Integer extensionSeconds) {
        Duration ext = extensionSeconds == null ? lease : Duration.ofSeconds(extensionSeconds);
        if (ext.isZero() || ext.isNegative()) throw new IllegalArgumentException("extensionSeconds must be positive");
        if (ext.compareTo(maxExtension) > 0)
            throw new IllegalArgumentException("extensionSeconds must not exceed " + maxExtension.toSeconds());
        return jobService.renewLease(jobId, worker.claimId(), attempt, ext);
    }

    public boolean complete(WorkerPrincipal worker, UUID jobId, int attempt) {
        return jobService.completeFenced(jobId, worker.claimId(), attempt);
    }

    public boolean fail(WorkerPrincipal worker, UUID jobId, int attempt, String reason) {
        return failWithEvidence(worker, jobId, attempt, reason == null || reason.isBlank() ? "Worker reported failure" : reason);
    }

    private boolean failWithEvidence(WorkerPrincipal worker, UUID jobId, int attempt, String reason) {
        PostureJob job = jobs.findById(jobId).orElse(null);
        if (job == null) return false;
        UUID endpointId = job.getEndpoint().getId();
        JobType type = job.getJobType();
        if (!jobService.failFenced(jobId, worker.claimId(), attempt, reason)) return false;
        outcomes.recordFailureEvidence(type, endpointId, jobId, reason);
        return true;
    }
}
