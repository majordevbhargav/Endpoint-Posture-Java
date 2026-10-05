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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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

    // ---- complete / fail ----
    @Test
    void markCompleteClearsTheErrorAndStampsCompletion() {
        PostureJob j = job(JobStatus.RUNNING, 2, 3);
        j.setErrorMessage("old failure");
        service.markComplete(j.getId());
        assertEquals(JobStatus.COMPLETE, j.getStatus());
        assertNull(j.getErrorMessage());
        assertNotNull(j.getCompletedAt());
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
        service.markFailed(j.getId(), "boom");

        assertEquals(JobStatus.QUEUED, j.getStatus());
        assertEquals("boom", j.getErrorMessage());
        assertNull(j.getCompletedAt());
        assertTrue(j.getNextAttemptAt().isAfter(Instant.now().plus(Duration.ofSeconds(30))));
        assertTrue(j.getNextAttemptAt().isBefore(Instant.now().plus(Duration.ofMinutes(2))));
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

    // ---- markFailedIfRunning ----
    @Test
    void markFailedIfRunningActsOnARunningJob() {
        PostureJob j = job(JobStatus.RUNNING, 1, 3);
        assertTrue(service.markFailedIfRunning(j.getId(), "stalled"));
        assertEquals(JobStatus.QUEUED, j.getStatus());
    }

    @Test
    void markFailedIfRunningLeavesFinishedJobsAlone() {
        for (JobStatus s : new JobStatus[]{JobStatus.COMPLETE, JobStatus.FAILED, JobStatus.QUEUED}) {
            PostureJob j = job(s, 1, 3);
            assertFalse(service.markFailedIfRunning(j.getId(), "stalled"), s.name());
            assertEquals(s, j.getStatus());
            assertNull(j.getErrorMessage());
        }
    }

    @Test
    void markFailedIfRunningOnAnUnknownJobIsFalse() {
        when(jobs.findById(any())).thenReturn(Optional.empty());
        assertFalse(service.markFailedIfRunning(UUID.randomUUID(), "x"));
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