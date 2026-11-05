package com.endpointposture.worker;

import com.endpointposture.diagnostic.DiagnosticService;
import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.hardware.HardwareHealthService;
import com.endpointposture.indicator.SecurityIndicatorService;
import com.endpointposture.job.JobService;
import com.endpointposture.job.JobSpecFactory;
import com.endpointposture.job.JobType;
import com.endpointposture.job.PostureJob;
import com.endpointposture.job.PostureJobRepository;
import com.endpointposture.job.spec.JobSpec;
import com.endpointposture.posture.AssessmentService;
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
    private final AssessmentService assessments;
    private final HardwareHealthService hardware;
    private final DiagnosticService diagnostics;
    private final SecurityIndicatorService indicators;
    private final Duration lease;

    public WorkerJobService(JobService jobService, JobSpecFactory specFactory, PostureJobRepository jobs,
            AssessmentService assessments, HardwareHealthService hardware, DiagnosticService diagnostics,
            SecurityIndicatorService indicators, @Value("${app.workers.lease-seconds:300}") long leaseSeconds) {
        this.jobService=jobService; this.specFactory=specFactory; this.jobs=jobs;
        this.assessments=assessments; this.hardware=hardware; this.diagnostics=diagnostics; this.indicators=indicators;
        this.lease=Duration.ofSeconds(Math.max(30, leaseSeconds));
    }

    public Optional<Claimed> claim(WorkerPrincipal worker) {
        Optional<PostureJob> claimed=jobService.claimNextJobInShard(worker.claimId(),worker.shard(),lease);
        if(claimed.isEmpty()) return Optional.empty();
        PostureJob job=claimed.get();
        Endpoint endpoint=job.getEndpoint();
        try {
            JobSpec spec=specFactory.build(job,endpoint);
            return Optional.of(new Claimed(job.getId(),job.getAttemptCount(),job.getJobType(),spec,job.getLeaseExpiresAt()));
        } catch(RuntimeException e) {
            failWithEvidence(worker,job.getId(),job.getAttemptCount(),"Could not build job spec: "+e.getMessage());
            return Optional.empty();
        }
    }
    public boolean complete(WorkerPrincipal worker,UUID jobId,int attempt) {
        return jobService.completeFenced(jobId,worker.claimId(),attempt);
    }
    public boolean fail(WorkerPrincipal worker,UUID jobId,int attempt,String reason) {
        return failWithEvidence(worker,jobId,attempt,reason==null||reason.isBlank()?"Worker reported failure":reason);
    }
    private boolean failWithEvidence(WorkerPrincipal worker,UUID jobId,int attempt,String reason) {
        PostureJob job=jobs.findById(jobId).orElse(null);
        if(job==null) return false;
        UUID endpointId=job.getEndpoint().getId();
        JobType type=job.getJobType();
        if(!jobService.failFenced(jobId,worker.claimId(),attempt,reason)) return false;
        switch(type) {
            case POSTURE_CHECK -> assessments.recordFailure(endpointId,jobId,reason);
            case HARDWARE_CHECK -> hardware.recordFailure(endpointId,jobId,reason);
            case DIAGNOSTIC_CHECK -> diagnostics.recordFailure(endpointId,jobId,reason);
            case SECURITY_CHECK -> indicators.recordFailure(endpointId,jobId,reason);
        }
        return true;
    }
}
