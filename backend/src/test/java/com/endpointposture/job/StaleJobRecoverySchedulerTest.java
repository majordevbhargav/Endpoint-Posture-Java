package com.endpointposture.job;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.hardware.HardwareHealthService;
import com.endpointposture.hardware.config.HardwareAgentProperties;
import com.endpointposture.posture.AssessmentService;
import com.endpointposture.posture.config.PostureAgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mirrors the style of JobServiceEnqueueIfDueTest: repository and
 * downstream services are mocked, and the sweep's decisions are asserted
 * through what it does or doesn't call on them.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StaleJobRecoverySchedulerTest {

    @Mock PostureJobRepository jobRepository;
    @Mock JobService jobService;
    @Mock AssessmentService assessmentService;
    @Mock HardwareHealthService hardwareHealthService;
    @Mock PostureAgentProperties postureProps;
    @Mock HardwareAgentProperties hardwareProps;

    StaleJobRecoveryScheduler scheduler;

    final UUID endpointId = UUID.randomUUID();
    final long marginSeconds = 60;

    @BeforeEach
    void setUp() {
        when(postureProps.getProcessTimeoutSeconds()).thenReturn(60);
        when(hardwareProps.getProcessTimeoutSeconds()).thenReturn(70);

        scheduler = new StaleJobRecoveryScheduler(
                jobRepository, jobService, assessmentService, hardwareHealthService,
                postureProps, hardwareProps, marginSeconds);
    }

    private PostureJob runningJob(JobType type, Instant startedAt) {
        Endpoint endpoint = Endpoint.builder().id(endpointId).macAddress("AA:BB:CC:DD:EE:FF").build();
        return PostureJob.builder()
                .id(UUID.randomUUID())
                .endpoint(endpoint)
                .jobType(type)
                .status(JobStatus.RUNNING)
                .startedAt(startedAt)
                .attemptCount(1)
                .maxAttempts(3)
                .build();
    }

    @Test
    void noStaleJobsRecoversNothing() {
        when(jobRepository.findStaleRunning(any())).thenReturn(List.of());

        scheduler.sweep();

        verify(jobService, never()).markFailedIfRunning(any(), anyString());
        verify(assessmentService, never()).recordFailure(any(), any(), anyString());
        verify(hardwareHealthService, never()).recordFailure(any(), any(), anyString());
    }

    @Test
    void stalePostureJob_isRecoveredWithAssessmentFailureEvidence() {
        PostureJob job = runningJob(JobType.POSTURE_CHECK, Instant.now().minusSeconds(200));
        when(jobRepository.findStaleRunning(any())).thenReturn(List.of(job));
        when(jobService.markFailedIfRunning(eq(job.getId()), anyString())).thenReturn(true);

        scheduler.sweep();

        verify(jobService).markFailedIfRunning(eq(job.getId()), anyString());
        verify(assessmentService).recordFailure(eq(endpointId), eq(job.getId()), anyString());
        verify(hardwareHealthService, never()).recordFailure(any(), any(), anyString());
    }

    @Test
    void staleHardwareJob_isRecoveredWithHardwareFailureEvidence() {
        PostureJob job = runningJob(JobType.HARDWARE_CHECK, Instant.now().minusSeconds(200));
        when(jobRepository.findStaleRunning(any())).thenReturn(List.of(job));
        when(jobService.markFailedIfRunning(eq(job.getId()), anyString())).thenReturn(true);

        scheduler.sweep();

        verify(jobService).markFailedIfRunning(eq(job.getId()), anyString());
        verify(hardwareHealthService).recordFailure(eq(endpointId), eq(job.getId()), anyString());
        verify(assessmentService, never()).recordFailure(any(), any(), anyString());
    }

    @Test
    void jobThatCompletedBetweenQueryAndActionWritesNoFailureEvidence() {
        // markFailedIfRunning returning false means JobWorker finished this
        // job for real in the gap between the sweep's query and its action -
        // no failure evidence should ever be written for it.
        PostureJob job = runningJob(JobType.POSTURE_CHECK, Instant.now().minusSeconds(200));
        when(jobRepository.findStaleRunning(any())).thenReturn(List.of(job));
        when(jobService.markFailedIfRunning(eq(job.getId()), anyString())).thenReturn(false);

        scheduler.sweep();

        verify(jobService).markFailedIfRunning(eq(job.getId()), anyString());
        verify(assessmentService, never()).recordFailure(any(), any(), anyString());
        verify(hardwareHealthService, never()).recordFailure(any(), any(), anyString());
    }

    @Test
    void hardwareJobWithinItsOwnTimeoutIsNotRecovered_evenIfReturnedByTheWideQuery() {
        // Repository query uses the WIDEST cutoff (hardware's 70s), so it may
        // legitimately return jobs that are within their own timeout - this
        // proves the per-type re-check (isActuallyStale) filters those out.
        // startedAt is 65s ago: past nothing at all given hardware's 70s
        // timeout + 60s margin (cutoff would be ~130s), so this job must NOT
        // be recovered despite being present in the mocked query result.
        PostureJob job = runningJob(JobType.HARDWARE_CHECK, Instant.now().minusSeconds(65));
        when(jobRepository.findStaleRunning(any())).thenReturn(List.of(job));

        scheduler.sweep();

        verify(jobService, never()).markFailedIfRunning(any(), anyString());
        verify(hardwareHealthService, never()).recordFailure(any(), any(), anyString());
    }

    @Test
    void multipleStaleJobsAreAllRecoveredIndependently() {
        PostureJob postureJob = runningJob(JobType.POSTURE_CHECK, Instant.now().minusSeconds(300));
        PostureJob hardwareJob = runningJob(JobType.HARDWARE_CHECK, Instant.now().minusSeconds(300));
        when(jobRepository.findStaleRunning(any())).thenReturn(List.of(postureJob, hardwareJob));
        when(jobService.markFailedIfRunning(any(), anyString())).thenReturn(true);

        scheduler.sweep();

        verify(jobService, times(2)).markFailedIfRunning(any(), anyString());
        verify(assessmentService).recordFailure(eq(endpointId), eq(postureJob.getId()), anyString());
        verify(hardwareHealthService).recordFailure(eq(endpointId), eq(hardwareJob.getId()), anyString());
    }

    @Test
    void repositoryFailureIsCaughtAndDoesNotThrow() {
        when(jobRepository.findStaleRunning(any())).thenThrow(new RuntimeException("db unreachable"));

        // Must not propagate - a thrown exception out of a @Scheduled method
        // would silently kill every future scheduled tick of this bean.
        scheduler.sweep();

        verify(jobService, never()).markFailedIfRunning(any(), anyString());
    }
}