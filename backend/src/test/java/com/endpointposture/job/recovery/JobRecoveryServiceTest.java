package com.endpointposture.job.recovery;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.job.JobOutcomeService;
import com.endpointposture.job.JobService;
import com.endpointposture.job.JobType;
import com.endpointposture.job.PostureJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobRecoveryServiceTest {

    @Mock JobService jobs;
    @Mock JobOutcomeService outcomes;
    @Mock JobRecoveryEventRepository events;

    private JobRecoveryService recovery;

    @BeforeEach
    void setUp() {
        recovery = new JobRecoveryService(jobs, outcomes, events);
    }

    private PostureJob runningJob() {
        Endpoint endpoint = Endpoint.builder()
                .id(UUID.randomUUID())
                .macAddress("AA:BB:CC:DD:EE:FF")
                .build();
        return PostureJob.builder()
                .id(UUID.randomUUID())
                .endpoint(endpoint)
                .jobType(JobType.POSTURE_CHECK)
                .status(com.endpointposture.job.JobStatus.RUNNING)
                .attemptCount(2)
                .claimedBy(UUID.randomUUID().toString())
                .leaseExpiresAt(Instant.now().minusSeconds(1))
                .build();
    }

    @Test
    void expiredLeaseTransitionsThenWritesEvidenceAndAudit() {
        PostureJob job = runningJob();
        when(jobs.failExpiredLeaseAttempt(job.getId(), 2, "lease expired")).thenReturn(true);

        assertTrue(recovery.recoverJob(job, RecoveryType.LEASE_EXPIRED, "lease expired"));

        verify(jobs).failExpiredLeaseAttempt(job.getId(), 2, "lease expired");
        verify(outcomes).recordFailureEvidence(JobType.POSTURE_CHECK,
                job.getEndpoint().getId(), job.getId(), "lease expired");
        verify(events).saveAndFlush(argThat(event ->
                event.getJobId().equals(job.getId())
                        && event.getEndpointId().equals(job.getEndpoint().getId())
                        && event.getEndpointMacAddress().equals("AA:BB:CC:DD:EE:FF")
                        && event.getAttemptCount() == 2
                        && event.getRecoveryType() == RecoveryType.LEASE_EXPIRED));
    }

    @Test
    void revokedWorkerUsesRevocationFence() {
        PostureJob job = runningJob();
        when(jobs.failRevokedWorkerAttempt(job.getId(), 2, "worker revoked")).thenReturn(true);

        assertTrue(recovery.recoverJob(job, RecoveryType.WORKER_REVOKED, "worker revoked"));

        verify(jobs).failRevokedWorkerAttempt(job.getId(), 2, "worker revoked");
        verify(jobs, never()).failExpiredLeaseAttempt(any(), anyInt(), any());
        verify(jobs, never()).failAttempt(any(), anyInt(), any());
        verify(events).saveAndFlush(any(JobRecoveryEvent.class));
    }

    @Test
    void supersededAttemptWritesNeitherEvidenceNorAudit() {
        PostureJob job = runningJob();
        when(jobs.failExpiredLeaseAttempt(job.getId(), 2, "lease expired")).thenReturn(false);

        assertFalse(recovery.recoverJob(job, RecoveryType.LEASE_EXPIRED, "lease expired"));

        verifyNoInteractions(outcomes, events);
    }

    @Test
    void auditFailurePropagatesSoOuterTransactionCanRollBack() {
        PostureJob job = runningJob();
        when(jobs.failExpiredLeaseAttempt(job.getId(), 2, "lease expired")).thenReturn(true);
        doThrow(new IllegalStateException("audit insert failed"))
                .when(events).saveAndFlush(any(JobRecoveryEvent.class));

        assertThrows(IllegalStateException.class,
                () -> recovery.recoverJob(job, RecoveryType.LEASE_EXPIRED, "lease expired"));
        verify(outcomes).recordFailureEvidence(any(), any(), any(), any());
    }
}
