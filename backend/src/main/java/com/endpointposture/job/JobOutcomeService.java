package com.endpointposture.job;

import com.endpointposture.diagnostic.DiagnosticService;
import com.endpointposture.hardware.HardwareHealthService;
import com.endpointposture.indicator.SecurityIndicatorService;
import com.endpointposture.posture.AssessmentService;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Applies evidence and job-state outcomes for an agent execution. */
@Service
public class JobOutcomeService {
    private final JobService jobService;
    private final AssessmentService assessmentService;
    private final HardwareHealthService hardwareHealthService;
    private final DiagnosticService diagnosticService;
    private final SecurityIndicatorService securityIndicatorService;

    public JobOutcomeService(JobService jobService, AssessmentService assessmentService,
                             HardwareHealthService hardwareHealthService, DiagnosticService diagnosticService,
                             SecurityIndicatorService securityIndicatorService) {
        this.jobService = jobService;
        this.assessmentService = assessmentService;
        this.hardwareHealthService = hardwareHealthService;
        this.diagnosticService = diagnosticService;
        this.securityIndicatorService = securityIndicatorService;
    }

    public boolean complete(UUID jobId, int attempt) {
        return jobService.completeAttempt(jobId, attempt);
    }

    /**
     * Transition first. If this attempt no longer owns the RUNNING job, do not
     * append failure evidence for a superseded execution.
     */
    public boolean fail(JobType type, UUID jobId, UUID endpointId, int attempt, String reason) {
        if (!jobService.failAttempt(jobId, attempt, reason)) {
            return false;
        }
        recordFailureEvidence(type, endpointId, jobId, reason);
        return true;
    }

    /** Centralized failure evidence recorder for all four job types. */
    public void recordFailureEvidence(JobType type, UUID endpointId, UUID jobId, String reason) {
        switch (type) {
            case POSTURE_CHECK -> assessmentService.recordFailure(endpointId, jobId, reason);
            case HARDWARE_CHECK -> hardwareHealthService.recordFailure(endpointId, jobId, reason);
            case DIAGNOSTIC_CHECK -> diagnosticService.recordFailure(endpointId, jobId, reason);
            case SECURITY_CHECK -> securityIndicatorService.recordFailure(endpointId, jobId, reason);
        }
    }
}
