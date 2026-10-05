package com.endpointposture.job;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Bounds the size of {@code posture_job}. Finished jobs ({@code COMPLETE} and
 * {@code FAILED}) older than {@code app.retention.job-days} (default 30) are
 * deleted. {@code QUEUED} and {@code RUNNING} jobs are never touched.
 *
 * <p>Deleting a job does not delete evidence: assessments, hardware reports,
 * diagnostics and indicator rows reference jobs with {@code ON DELETE SET NULL},
 * so they simply lose the link. Recheck decisions only look back hours (4 h,
 * 24 h, 6 h backoff), far inside the retention window.</p>
 */
@Service
public class JobRetentionService {

    private static final List<JobStatus> PRUNABLE = List.of(JobStatus.COMPLETE, JobStatus.FAILED);

    private final PostureJobRepository jobs;
    private final int keepDays;

    /**
     * @param keepDays how many days of finished jobs to keep; must be at least 1
     * @throws IllegalArgumentException if {@code keepDays} is below 1
     */
    public JobRetentionService(PostureJobRepository jobs,
                               @Value("${app.retention.job-days:30}") int keepDays) {
        if (keepDays < 1) {
            throw new IllegalArgumentException("app.retention.job-days must be at least 1");
        }
        this.jobs = jobs;
        this.keepDays = keepDays;
    }

    /** @return how many finished jobs were deleted */
    @Transactional
    public int pruneOldJobs() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(keepDays));
        return jobs.deleteFinishedBefore(cutoff, PRUNABLE);
    }
}