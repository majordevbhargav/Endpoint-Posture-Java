package com.endpointposture.job;

import com.endpointposture.diagnostic.DiagnosticService;
import com.endpointposture.hardware.HardwareHealthService;
import com.endpointposture.indicator.SecurityIndicatorService;
import com.endpointposture.posture.AssessmentService;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Applies evidence and job-state outcomes for an embedded agent execution. */
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

    public void complete(UUID jobId) {
        jobService.markComplete(jobId);
    }

    /** Writes failure evidence before marking the job failed, preserving the existing behavior. */
    public void fail(JobType type, UUID jobId, UUID endpointId, String reason) {
        switch (type) {
            case POSTURE_CHECK -> assessmentService.recordFailure(endpointId, jobId, reason);
            case HARDWARE_CHECK -> hardwareHealthService.recordFailure(endpointId, jobId, reason);
            case DIAGNOSTIC_CHECK -> diagnosticService.recordFailure(endpointId, jobId, reason);
            case SECURITY_CHECK -> securityIndicatorService.recordFailure(endpointId, jobId, reason);
        }
        jobService.markFailed(jobId, reason);
    }
}
