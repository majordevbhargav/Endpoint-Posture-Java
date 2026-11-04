package com.endpointposture.job;

import com.endpointposture.diagnostic.DiagnosticService;
import com.endpointposture.hardware.HardwareHealthService;
import com.endpointposture.indicator.SecurityIndicatorService;
import com.endpointposture.policy.PolicyService;
import com.endpointposture.posture.AssessmentService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.UUID;

import static org.mockito.Mockito.*;

class JobOutcomeServiceTest {
    private final JobService jobs = mock(JobService.class);
    private final AssessmentService posture = mock(AssessmentService.class);
    private final HardwareHealthService hardware = mock(HardwareHealthService.class);
    private final DiagnosticService diagnostics = mock(DiagnosticService.class);
    private final SecurityIndicatorService security = mock(SecurityIndicatorService.class);
    private final JobOutcomeService outcomes = new JobOutcomeService(jobs, posture, hardware, diagnostics, security);

    @Test
    void postureFailureEvidenceIsWrittenBeforeJobIsFailed() {
        UUID jobId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        outcomes.fail(JobType.POSTURE_CHECK, jobId, endpointId, "timeout");
        InOrder order = inOrder(posture, jobs);
        order.verify(posture).recordFailure(endpointId, jobId, "timeout");
        order.verify(jobs).markFailed(jobId, "timeout");
    }

    @Test
    void routesOtherFailureEvidenceToTheMatchingService() {
        UUID jobId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();

        outcomes.fail(JobType.HARDWARE_CHECK, jobId, endpointId, "hardware");
        verify(hardware).recordFailure(endpointId, jobId, "hardware");

        outcomes.fail(JobType.DIAGNOSTIC_CHECK, jobId, endpointId, "diagnostic");
        verify(diagnostics).recordFailure(endpointId, jobId, "diagnostic");

        outcomes.fail(JobType.SECURITY_CHECK, jobId, endpointId, "security");
        verify(security).recordFailure(endpointId, jobId, "security");
    }

    @Test
    void completionDelegatesToJobService() {
        UUID id = UUID.randomUUID();
        outcomes.complete(id);
        verify(jobs).markComplete(id);
    }
}
