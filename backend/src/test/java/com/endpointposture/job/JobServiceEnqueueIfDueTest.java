package com.endpointposture.job;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobServiceEnqueueIfDueTest {

    @Mock PostureJobRepository jobs;
    @Mock EndpointRepository endpoints;
    JobService service;

    final UUID id = UUID.randomUUID();
    final Duration interval = Duration.ofHours(4);
    final Duration backoff = Duration.ofHours(6);

    @BeforeEach
    void setUp() {
        service = new JobService(jobs, endpoints);
        when(endpoints.findById(id)).thenReturn(Optional.of(Endpoint.builder().id(id).build()));
        when(jobs.save(any())).thenAnswer(i -> i.getArgument(0));
        when(jobs.existsByEndpoint_IdAndJobTypeAndStatusIn(eq(id), any(), anyCollection())).thenReturn(false);
        when(jobs.findFirstByEndpoint_IdAndJobTypeAndStatusOrderByCompletedAtDesc(eq(id), any(), any()))
                .thenReturn(Optional.empty());
        when(jobs.findFirstByEndpoint_IdAndJobTypeOrderByCreatedAtDesc(eq(id), any()))
                .thenReturn(Optional.empty());
    }

    private PostureJob job(JobStatus status, Instant completedAt) {
        return PostureJob.builder().status(status).completedAt(completedAt).jobType(JobType.POSTURE_CHECK).build();
    }

    @Test
    void neverRunEnqueues() {
        assertTrue(service.enqueueIfDue(id, JobType.POSTURE_CHECK, interval, backoff));
        verify(jobs).save(any());
    }

    @Test
    void alreadyQueuedOrRunningDoesNotEnqueue() {
        when(jobs.existsByEndpoint_IdAndJobTypeAndStatusIn(eq(id), any(), anyCollection())).thenReturn(true);
        assertFalse(service.enqueueIfDue(id, JobType.POSTURE_CHECK, interval, backoff));
        verify(jobs, never()).save(any());
    }

    @Test
    void completedRecentlyDoesNotEnqueue() {
        when(jobs.findFirstByEndpoint_IdAndJobTypeAndStatusOrderByCompletedAtDesc(eq(id), any(), any()))
                .thenReturn(Optional.of(job(JobStatus.COMPLETE, Instant.now().minus(Duration.ofHours(1)))));
        assertFalse(service.enqueueIfDue(id, JobType.POSTURE_CHECK, interval, backoff));
    }

    @Test
    void completedLongAgoEnqueues() {
        when(jobs.findFirstByEndpoint_IdAndJobTypeAndStatusOrderByCompletedAtDesc(eq(id), any(), any()))
                .thenReturn(Optional.of(job(JobStatus.COMPLETE, Instant.now().minus(Duration.ofHours(5)))));
        assertTrue(service.enqueueIfDue(id, JobType.POSTURE_CHECK, interval, backoff));
    }

    @Test
    void failedRecentlyDoesNotEnqueue() {
        when(jobs.findFirstByEndpoint_IdAndJobTypeOrderByCreatedAtDesc(eq(id), any()))
                .thenReturn(Optional.of(job(JobStatus.FAILED, Instant.now().minus(Duration.ofHours(1)))));
        assertFalse(service.enqueueIfDue(id, JobType.POSTURE_CHECK, interval, backoff));
    }

    @Test
    void failedLongAgoEnqueues() {
        when(jobs.findFirstByEndpoint_IdAndJobTypeOrderByCreatedAtDesc(eq(id), any()))
                .thenReturn(Optional.of(job(JobStatus.FAILED, Instant.now().minus(Duration.ofHours(7)))));
        assertTrue(service.enqueueIfDue(id, JobType.POSTURE_CHECK, interval, backoff));
    }
}