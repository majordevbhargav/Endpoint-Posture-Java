package com.endpointposture.job;

import com.endpointposture.diagnostic.config.DiagnosticAgentProperties;
import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.hardware.config.HardwareAgentProperties;
import com.endpointposture.indicator.config.SecurityIndicatorAgentProperties;
import com.endpointposture.job.recovery.JobRecoveryService;
import com.endpointposture.job.recovery.RecoveryType;
import com.endpointposture.posture.config.PostureAgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StaleJobRecoverySchedulerTest {

    @Mock PostureJobRepository jobs;
    @Mock JobRecoveryService recovery;
    @Mock PostureAgentProperties posture;
    @Mock HardwareAgentProperties hardware;
    @Mock DiagnosticAgentProperties diagnostics;
    @Mock SecurityIndicatorAgentProperties security;

    private StaleJobRecoveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        when(posture.getProcessTimeoutSeconds()).thenReturn(60);
        when(hardware.getProcessTimeoutSeconds()).thenReturn(70);
        when(diagnostics.getProcessTimeoutSeconds()).thenReturn(130);
        when(security.getProcessTimeoutSeconds()).thenReturn(170);
        when(jobs.findStaleRunning(any(Instant.class), any(Instant.class))).thenReturn(List.of());
        when(jobs.findRunningJobsOwnedByRevokedWorkers()).thenReturn(List.of());
        scheduler = new StaleJobRecoveryScheduler(
                jobs, recovery, posture, hardware, diagnostics, security, 60);
    }

    private PostureJob job(JobType type, Instant startedAt) {
        Endpoint endpoint = Endpoint.builder()
                .id(UUID.randomUUID())
                .macAddress("AA:BB:CC:DD:EE:FF")
                .build();
        return PostureJob.builder()
                .id(UUID.randomUUID())
                .endpoint(endpoint)
                .jobType(type)
                .status(JobStatus.RUNNING)
                .startedAt(startedAt)
                .attemptCount(2)
                .maxAttempts(3)
                .build();
    }

    @Test
    void emptySweepDoesNothing() {
        scheduler.sweep();
        verifyNoInteractions(recovery);
    }

    @Test
    void recoversLocalJobOnlyAfterItsTypeSpecificTimeout() {
        PostureJob stale = job(JobType.DIAGNOSTIC_CHECK, Instant.now().minusSeconds(300));
        when(jobs.findStaleRunning(any(Instant.class), any(Instant.class))).thenReturn(List.of(stale));
        when(recovery.recoverJob(eq(stale), eq(RecoveryType.PROCESS_TIMEOUT), any())).thenReturn(true);

        scheduler.sweep();

        verify(recovery).recoverJob(eq(stale), eq(RecoveryType.PROCESS_TIMEOUT), any());
    }

    @Test
    void doesNotRecoverLocalJobStillWithinItsTypeSpecificTimeout() {
        PostureJob notStale = job(JobType.SECURITY_CHECK, Instant.now().minusSeconds(200));
        when(jobs.findStaleRunning(any(Instant.class), any(Instant.class))).thenReturn(List.of(notStale));

        scheduler.sweep();

        verifyNoInteractions(recovery);
    }

    @Test
    void recoversExpiredLeaseButNotLiveLease() {
        PostureJob expired = job(JobType.POSTURE_CHECK, Instant.now().minusSeconds(10));
        expired.setClaimedBy(UUID.randomUUID().toString());
        expired.setLeaseExpiresAt(Instant.now().minusSeconds(5));
        PostureJob live = job(JobType.POSTURE_CHECK, Instant.now().minusSeconds(5000));
        live.setClaimedBy(UUID.randomUUID().toString());
        live.setLeaseExpiresAt(Instant.now().plusSeconds(120));
        when(jobs.findStaleRunning(any(Instant.class), any(Instant.class))).thenReturn(List.of(expired, live));
        when(recovery.recoverJob(eq(expired), eq(RecoveryType.LEASE_EXPIRED), any())).thenReturn(true);

        scheduler.sweep();

        verify(recovery).recoverJob(eq(expired), eq(RecoveryType.LEASE_EXPIRED), any());
        verify(recovery, never()).recoverJob(eq(live), any(), any());
    }

    @Test
    void revokedWorkerRecoveryTakesPrecedenceAndIsDeduplicated() {
        PostureJob revoked = job(JobType.POSTURE_CHECK, Instant.now().minusSeconds(10));
        revoked.setClaimedBy(UUID.randomUUID().toString());
        revoked.setLeaseExpiresAt(Instant.now().minusSeconds(5));
        when(jobs.findStaleRunning(any(Instant.class), any(Instant.class))).thenReturn(List.of(revoked));
        when(jobs.findRunningJobsOwnedByRevokedWorkers()).thenReturn(List.of(revoked));
        when(recovery.recoverJob(eq(revoked), eq(RecoveryType.WORKER_REVOKED), any())).thenReturn(true);

        scheduler.sweep();

        verify(recovery, times(1)).recoverJob(eq(revoked), eq(RecoveryType.WORKER_REVOKED), any());
        verify(recovery, never()).recoverJob(eq(revoked), eq(RecoveryType.LEASE_EXPIRED), any());
    }

    @Test
    void oneCandidateFailureDoesNotPreventFollowingCandidate() {
        PostureJob first = job(JobType.POSTURE_CHECK, Instant.now().minusSeconds(300));
        PostureJob second = job(JobType.HARDWARE_CHECK, Instant.now().minusSeconds(300));
        when(jobs.findStaleRunning(any(Instant.class), any(Instant.class))).thenReturn(List.of(first, second));
        when(recovery.recoverJob(eq(first), eq(RecoveryType.PROCESS_TIMEOUT), any()))
                .thenThrow(new IllegalStateException("simulated failure"));
        when(recovery.recoverJob(eq(second), eq(RecoveryType.PROCESS_TIMEOUT), any())).thenReturn(true);

        scheduler.sweep();

        verify(recovery).recoverJob(eq(first), eq(RecoveryType.PROCESS_TIMEOUT), any());
        verify(recovery).recoverJob(eq(second), eq(RecoveryType.PROCESS_TIMEOUT), any());
    }
}
