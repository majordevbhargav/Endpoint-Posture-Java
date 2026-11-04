package com.endpointposture.job;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointNotFoundException;
import com.endpointposture.endpoint.EndpointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobServiceStateTest {

    @Mock PostureJobRepository jobs;
    @Mock EndpointRepository endpoints;
    JobService service;

    final UUID endpointId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new JobService(jobs, endpoints);
        when(jobs.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private PostureJob job(JobStatus status, int attempts, int max) {
        PostureJob j = PostureJob.builder().id(UUID.randomUUID())
                .endpoint(Endpoint.builder().id(endpointId).build())
                .jobType(JobType.POSTURE_CHECK).status(status)
                .attemptCount(attempts).maxAttempts(max).build();
        when(jobs.findById(j.getId())).thenReturn(Optional.of(j));
        return j;
    }

    // ---- enqueue ----
    @Test
    void enqueueCreatesAQueuedJobWithThreeAttempts() {
        when(endpoints.findById(endpointId)).thenReturn(Optional.of(Endpoint.builder().id(endpointId).build()));
        PostureJob j = service.enqueue(endpointId, JobType.HARDWARE_CHECK, 10);
        assertEquals(JobStatus.QUEUED, j.getStatus());
        assertEquals(JobType.HARDWARE_CHECK, j.getJobType());
        assertEquals(10, j.getPriority());
        assertEquals(0, j.getAttemptCount());
        assertEquals(3, j.getMaxAttempts());
    }

    @Test
    void enqueueForAnUnknownEndpointThrowsAndSavesNothing() {
        when(endpoints.findById(endpointId)).thenReturn(Optional.empty());
        assertThrows(EndpointNotFoundException.class, () -> service.enqueue(endpointId, JobType.POSTURE_CHECK, 0));
        verify(jobs, never()).save(any());
    }

    // ---- claim ----
    @Test
    void claimMarksRunningStampsStartAndCountsTheAttempt() {
        PostureJob j = job(JobStatus.QUEUED, 0, 3);
        j.setEndpoint(Endpoint.builder().id(endpointId).macAddress("AA:BB:CC:DD:EE:FF").build());
        when(jobs.findNextClaimable()).thenReturn(Optional.of(j));

        PostureJob claimed = service.claimNextJob().orElseThrow();

        assertEquals(JobStatus.RUNNING, claimed.getStatus());
        assertEquals(1, claimed.getAttemptCount());
        assertNotNull(claimed.getStartedAt());
    }

    @Test
    void claimWithNothingEligibleIsEmpty() {
        when(jobs.findNextClaimable()).thenReturn(Optional.empty());
        assertTrue(service.claimNextJob().isEmpty());
        verify(jobs, never()).save(any());
    }

    @Test
    void leasedClaimRecordsOwnerLeaseAndFencingToken() {
        PostureJob j = job(JobStatus.QUEUED, 0, 3);
        j.setEndpoint(Endpoint.builder().id(endpointId).macAddress("AA:BB:CC:DD:EE:FF").build());
        when(jobs.findNextClaimable()).thenReturn(Optional.of(j));
        Instant before = Instant.now();

        PostureJob claimed = service.claimNextJob("worker-1", Duration.ofMinutes(2)).orElseThrow();

        assertEquals(JobStatus.RUNNING, claimed.getStatus());
        assertEquals("worker-1", claimed.getClaimedBy());
        assertEquals(1, claimed.getAttemptCount());
        assertTrue(claimed.getLeaseExpiresAt().isAfter(before.plusSeconds(119)));
        assertTrue(claimed.getLeaseExpiresAt().isBefore(Instant.now().plusSeconds(121)));
    }

    @Test
    void localClaimClearsAStaleLease() {
        PostureJob j = job(JobStatus.QUEUED, 1, 3);
        j.setEndpoint(Endpoint.builder().id(endpointId).macAddress("AA:BB:CC:DD:EE:FF").build());
        j.setClaimedBy("old-worker");
        j.setLeaseExpiresAt(Instant.now().minusSeconds(60));
        when(jobs.findNextClaimable()).thenReturn(Optional.of(j));

        PostureJob claimed = service.claimNextJob().orElseThrow();

        assertNull(claimed.getClaimedBy());
        assertNull(claimed.getLeaseExpiresAt());
        assertEquals(2, claimed.getAttemptCount());
    }

    @Test
    void leasedClaimRejectsABlankWorkerOrNonPositiveLeaseWithoutTouchingTheQueue() {
        assertThrows(IllegalArgumentException.class, () -> service.claimNextJob(" ", Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> service.claimNextJob(null, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> service.claimNextJob("w", null));
        assertThrows(IllegalArgumentException.class, () -> service.claimNextJob("w", Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> service.claimNextJob("w", Duration.ofSeconds(-1)));
        verify(jobs, never()).findNextClaimable();
    }

    // ---- remote worker fencing ----
    @Test
    void completeFencedReturnsTrueOnlyWhenRepositoryAcceptsTheCurrentAttempt() {
        UUID jobId = UUID.randomUUID();
        when(jobs.completeFenced(jobId, "worker-1", 2)).thenReturn(1);

        assertTrue(service.completeFenced(jobId, "worker-1", 2));

        verify(jobs).completeFenced(jobId, "worker-1", 2);
    }

    @Test
    void completeFencedReturnsFalseWhenRepositoryRejectsAStaleAttempt() {
        UUID jobId = UUID.randomUUID();
        when(jobs.completeFenced(jobId, "worker-1", 1)).thenReturn(0);

        assertFalse(service.completeFenced(jobId, "worker-1", 1));
    }

    @Test
    void failFencedMapsAcceptedAndRejectedUpdatesToBoolean() {
        UUID jobId = UUID.randomUUID();
        when(jobs.failFenced(jobId, "worker-2", 3, "agent failed")).thenReturn(1);
        when(jobs.failFenced(jobId, "worker-2", 2, "stale failure")).thenReturn(0);

        assertTrue(service.failFenced(jobId, "worker-2", 3, "agent failed"));
        assertFalse(service.failFenced(jobId, "worker-2", 2, "stale failure"));
    }

    @Test
    void fencedUpdatesRejectMissingIdentityOrInvalidAttemptBeforeDatabaseAccess() {
        UUID jobId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> service.completeFenced(null, "worker", 1));
        assertThrows(IllegalArgumentException.class, () -> service.completeFenced(jobId, " ", 1));
        assertThrows(IllegalArgumentException.class, () -> service.completeFenced(jobId, "worker", 0));
        assertThrows(IllegalArgumentException.class, () -> service.failFenced(jobId, null, 1, "failure"));

        verify(jobs, never()).completeFenced(any(), any(), anyInt());
        verify(jobs, never()).failFenced(any(), any(), anyInt(), any());
    }

    // ---- embedded attempt fencing ----
    @Test
    void completeAttemptMapsAcceptedAndRejectedRepositoryUpdates() {
        UUID id = UUID.randomUUID();
        when(jobs.completeRunningAttempt(id, 2)).thenReturn(1).thenReturn(0);
        assertTrue(service.completeAttempt(id, 2));
        assertFalse(service.completeAttempt(id, 2));
        verify(jobs, times(2)).completeRunningAttempt(id, 2);
    }

    @Test
    void failAttemptMapsAcceptedAndRejectedRepositoryUpdates() {
        UUID id = UUID.randomUUID();
        when(jobs.failRunningAttempt(id, 2, "failed")).thenReturn(1).thenReturn(0);
        assertTrue(service.failAttempt(id, 2, "failed"));
        assertFalse(service.failAttempt(id, 2, "failed"));
        verify(jobs, times(2)).failRunningAttempt(id, 2, "failed");
    }

    @Test
    void embeddedAttemptUpdatesRejectNullIdsAndNonPositiveAttempts() {
        assertThrows(IllegalArgumentException.class, () -> service.completeAttempt(null, 1));
        assertThrows(IllegalArgumentException.class, () -> service.completeAttempt(UUID.randomUUID(), 0));
        assertThrows(IllegalArgumentException.class, () -> service.failAttempt(null, 1, "x"));
        assertThrows(IllegalArgumentException.class, () -> service.failAttempt(UUID.randomUUID(), 0, "x"));
        verify(jobs, never()).completeRunningAttempt(any(), anyInt());
        verify(jobs, never()).failRunningAttempt(any(), anyInt(), any());
    }

    // ---- complete / fail ----
    @Test
    void markCompleteClearsTheErrorAndStampsCompletion() {
        PostureJob j = job(JobStatus.RUNNING, 2, 3);
        j.setErrorMessage("old failure");
        j.setClaimedBy("worker");
        j.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        service.markComplete(j.getId());
        assertEquals(JobStatus.COMPLETE, j.getStatus());
        assertNull(j.getErrorMessage());
        assertNotNull(j.getCompletedAt());
        assertNull(j.getClaimedBy());
        assertNull(j.getLeaseExpiresAt());
    }

    @Test
    void markCompleteOnAnUnknownIdIsIgnored() {
        when(jobs.findById(any())).thenReturn(Optional.empty());
        service.markComplete(UUID.randomUUID());
        verify(jobs, never()).save(any());
    }

    @Test
    void failureWithAttemptsLeftRequeuesWithABackoffInTheFuture() {
        PostureJob j = job(JobStatus.RUNNING, 1, 3);
        j.setClaimedBy("worker");
        j.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        service.markFailed(j.getId(), "boom");

        assertEquals(JobStatus.QUEUED, j.getStatus());
        assertEquals("boom", j.getErrorMessage());
        assertNull(j.getCompletedAt());
        assertTrue(j.getNextAttemptAt().isAfter(Instant.now().plus(Duration.ofSeconds(30))));
        assertTrue(j.getNextAttemptAt().isBefore(Instant.now().plus(Duration.ofMinutes(2))));
        assertNull(j.getClaimedBy());
        assertNull(j.getLeaseExpiresAt());
    }

    @Test
    void backoffGrowsWithAttemptsButIsCappedAtFifteenMinutes() {
        PostureJob j = job(JobStatus.RUNNING, 20, 30);
        service.markFailed(j.getId(), "boom");
        assertTrue(j.getNextAttemptAt().isAfter(Instant.now().plus(Duration.ofMinutes(14))));
        assertTrue(j.getNextAttemptAt().isBefore(Instant.now().plus(Duration.ofMinutes(16))));
    }

    @Test
    void failureOnTheLastAttemptEndsInFailedAndStaysThere() {
        PostureJob j = job(JobStatus.RUNNING, 3, 3);
        service.markFailed(j.getId(), "final");
        assertEquals(JobStatus.FAILED, j.getStatus());
        assertNotNull(j.getCompletedAt());
    }

    // ---- reconnect enqueue ----
    @Test
    void enqueueIfDueOnReconnectSkipsWhenAJobIsAlreadyPending() {
        when(jobs.existsByEndpoint_IdAndJobTypeAndStatusIn(eq(endpointId), eq(JobType.POSTURE_CHECK), anyCollection()))
                .thenReturn(true);
        service.enqueueIfDue(endpointId, JobType.POSTURE_CHECK);
        verify(jobs, never()).save(any());
    }

    @Test
    void enqueueIfDueOnReconnectEnqueuesAtAutomaticPriority() {
        when(jobs.existsByEndpoint_IdAndJobTypeAndStatusIn(any(), any(), anyCollection())).thenReturn(false);
        when(endpoints.findById(endpointId)).thenReturn(Optional.of(Endpoint.builder().id(endpointId).build()));
        service.enqueueIfDue(endpointId, JobType.POSTURE_CHECK);
        ArgumentCaptor<PostureJob> c = ArgumentCaptor.forClass(PostureJob.class);
        verify(jobs).save(c.capture());
        assertEquals(0, c.getValue().getPriority());
    }
}