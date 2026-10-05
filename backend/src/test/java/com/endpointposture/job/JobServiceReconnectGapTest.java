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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S4: a reconnect must not queue a new check when one completed recently. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobServiceReconnectGapTest {

    @Mock PostureJobRepository jobs;
    @Mock EndpointRepository endpoints;

    final UUID id = UUID.randomUUID();
    JobService service;

    @BeforeEach
    void setUp() {
        service = new JobService(jobs, endpoints); // default 60 minute gap
        when(endpoints.findById(id)).thenReturn(Optional.of(Endpoint.builder().id(id).build()));
        when(jobs.save(any())).thenAnswer(i -> i.getArgument(0));
        when(jobs.existsByEndpoint_IdAndJobTypeAndStatusIn(eq(id), any(), anyCollection())).thenReturn(false);
        when(jobs.findFirstByEndpoint_IdAndJobTypeAndStatusOrderByCompletedAtDesc(
                eq(id), eq(JobType.POSTURE_CHECK), eq(JobStatus.COMPLETE))).thenReturn(Optional.empty());
    }

    private void lastCompletedAgo(Duration ago) {
        PostureJob done = PostureJob.builder().jobType(JobType.POSTURE_CHECK).status(JobStatus.COMPLETE)
                .completedAt(Instant.now().minus(ago)).build();
        when(jobs.findFirstByEndpoint_IdAndJobTypeAndStatusOrderByCompletedAtDesc(
                eq(id), eq(JobType.POSTURE_CHECK), eq(JobStatus.COMPLETE))).thenReturn(Optional.of(done));
    }

    @Test
    void neverCheckedEnqueuesOnReconnect() {
        service.enqueueIfDue(id, JobType.POSTURE_CHECK);
        verify(jobs).save(any());
    }

    @Test
    void checkedTenMinutesAgoIsSkipped() {
        lastCompletedAgo(Duration.ofMinutes(10));
        service.enqueueIfDue(id, JobType.POSTURE_CHECK);
        verify(jobs, never()).save(any());
    }

    @Test
    void checkedTwoHoursAgoEnqueues() {
        lastCompletedAgo(Duration.ofHours(2));
        service.enqueueIfDue(id, JobType.POSTURE_CHECK);
        verify(jobs).save(any());
    }

    @Test
    void zeroGapAlwaysEnqueuesEvenRightAfterACheck() {
        service = new JobService(jobs, endpoints, 0L);
        lastCompletedAgo(Duration.ofSeconds(5));
        service.enqueueIfDue(id, JobType.POSTURE_CHECK);
        verify(jobs).save(any());
    }

    @Test
    void anAlreadyPendingJobStillWinsOverEverythingElse() {
        when(jobs.existsByEndpoint_IdAndJobTypeAndStatusIn(eq(id), any(), anyCollection())).thenReturn(true);
        service.enqueueIfDue(id, JobType.POSTURE_CHECK);
        verify(jobs, never()).save(any());
    }
}