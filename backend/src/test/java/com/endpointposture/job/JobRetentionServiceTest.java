package com.endpointposture.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S1: retention deletes only old FINISHED jobs and rejects a nonsensical window. */
@ExtendWith(MockitoExtension.class)
class JobRetentionServiceTest {

    @Mock PostureJobRepository jobs;
    @Captor ArgumentCaptor<Instant> cutoff;
    @Captor ArgumentCaptor<Collection<JobStatus>> statuses;

    @Test
    void deletesFinishedJobsOlderThanTheConfiguredDays() {
        when(jobs.deleteFinishedBefore(any(), anyCollection())).thenReturn(5);

        int deleted = new JobRetentionService(jobs, 30).pruneOldJobs();

        assertEquals(5, deleted);
        verify(jobs).deleteFinishedBefore(cutoff.capture(), statuses.capture());
        Instant expected = Instant.now().minus(Duration.ofDays(30));
        assertTrue(Duration.between(cutoff.getValue(), expected).abs().compareTo(Duration.ofMinutes(1)) < 0);
    }

    @Test
    void neverTouchesQueuedOrRunningJobs() {
        new JobRetentionService(jobs, 30).pruneOldJobs();

        verify(jobs).deleteFinishedBefore(any(), statuses.capture());
        assertTrue(statuses.getValue().containsAll(java.util.List.of(JobStatus.COMPLETE, JobStatus.FAILED)));
        assertFalse(statuses.getValue().contains(JobStatus.QUEUED));
        assertFalse(statuses.getValue().contains(JobStatus.RUNNING));
    }

    @Test
    void aWindowBelowOneDayIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new JobRetentionService(jobs, 0));
        assertThrows(IllegalArgumentException.class, () -> new JobRetentionService(jobs, -5));
    }
}