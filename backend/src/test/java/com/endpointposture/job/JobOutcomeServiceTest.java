package com.endpointposture.job;

import com.endpointposture.diagnostic.DiagnosticService;
import com.endpointposture.hardware.HardwareHealthService;
import com.endpointposture.indicator.SecurityIndicatorService;
import com.endpointposture.posture.AssessmentService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class JobOutcomeServiceTest {
    private final JobService jobs = mock(JobService.class);
    private final AssessmentService posture = mock(AssessmentService.class);
    private final HardwareHealthService hardware = mock(HardwareHealthService.class);
    private final DiagnosticService diagnostics = mock(DiagnosticService.class);
    private final SecurityIndicatorService security = mock(SecurityIndicatorService.class);
    private final JobOutcomeService outcomes = new JobOutcomeService(jobs, posture, hardware, diagnostics, security);

    @Test
    void acceptedFailuresWriteEvidenceForEachJobType() {
        UUID jobId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        when(jobs.failAttempt(jobId, 2, "timeout")).thenReturn(true);
        when(jobs.failAttempt(jobId, 2, "hardware")).thenReturn(true);
        when(jobs.failAttempt(jobId, 2, "diagnostic")).thenReturn(true);
        when(jobs.failAttempt(jobId, 2, "security")).thenReturn(true);

        assertTrue(outcomes.fail(JobType.POSTURE_CHECK, jobId, endpointId, 2, "timeout"));
        assertTrue(outcomes.fail(JobType.HARDWARE_CHECK, jobId, endpointId, 2, "hardware"));
        assertTrue(outcomes.fail(JobType.DIAGNOSTIC_CHECK, jobId, endpointId, 2, "diagnostic"));
        assertTrue(outcomes.fail(JobType.SECURITY_CHECK, jobId, endpointId, 2, "security"));

        verify(posture).recordFailure(endpointId, jobId, "timeout");
        verify(hardware).recordFailure(endpointId, jobId, "hardware");
        verify(diagnostics).recordFailure(endpointId, jobId, "diagnostic");
        verify(security).recordFailure(endpointId, jobId, "security");
    }

    @Test
    void recordFailureEvidenceDispatchesToCorrespondingServices() {
        UUID jobId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();

        outcomes.recordFailureEvidence(JobType.POSTURE_CHECK, endpointId, jobId, "posture error");
        outcomes.recordFailureEvidence(JobType.HARDWARE_CHECK, endpointId, jobId, "hardware error");
        outcomes.recordFailureEvidence(JobType.DIAGNOSTIC_CHECK, endpointId, jobId, "diag error");
        outcomes.recordFailureEvidence(JobType.SECURITY_CHECK, endpointId, jobId, "sec error");

        verify(posture).recordFailure(endpointId, jobId, "posture error");
        verify(hardware).recordFailure(endpointId, jobId, "hardware error");
        verify(diagnostics).recordFailure(endpointId, jobId, "diag error");
        verify(security).recordFailure(endpointId, jobId, "sec error");
    }

    @Test
    void supersededFailuresWriteNoEvidence() {
        UUID jobId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        when(jobs.failAttempt(jobId, 1, "stale")).thenReturn(false);

        assertFalse(outcomes.fail(JobType.POSTURE_CHECK, jobId, endpointId, 1, "stale"));
        assertFalse(outcomes.fail(JobType.HARDWARE_CHECK, jobId, endpointId, 1, "stale"));
        assertFalse(outcomes.fail(JobType.DIAGNOSTIC_CHECK, jobId, endpointId, 1, "stale"));
        assertFalse(outcomes.fail(JobType.SECURITY_CHECK, jobId, endpointId, 1, "stale"));

        verifyNoInteractions(posture, hardware, diagnostics, security);
    }

    @Test
    void completionDelegatesToAttemptFencedJobService() {
        UUID id = UUID.randomUUID();
        when(jobs.completeAttempt(id, 2)).thenReturn(true);
        assertTrue(outcomes.complete(id, 2));
        verify(jobs).completeAttempt(id, 2);
    }
}
