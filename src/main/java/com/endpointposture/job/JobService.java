package com.endpointposture.job;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointRepository;
import com.endpointposture.endpoint.EndpointNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobService {

    private final PostureJobRepository jobRepository;
    private final EndpointRepository endpointRepository;

    public JobService(PostureJobRepository jobRepository, EndpointRepository endpointRepository) {
        this.jobRepository = jobRepository;
        this.endpointRepository = endpointRepository;
    }

    @Transactional
    public PostureJob enqueue(UUID endpointId, JobType type, int priority) {
        Endpoint endpoint = endpointRepository.findById(endpointId)
                .orElseThrow(() -> new EndpointNotFoundException(endpointId.toString()));

        PostureJob job = PostureJob.builder()
                .endpoint(endpoint)
                .jobType(type)
                .status(JobStatus.QUEUED)
                .priority(priority)
                .attemptCount(0)
                .maxAttempts(3)
                .build();

        return jobRepository.save(job);
    }

    /**
     * Claims and locks the next eligible job in one transaction: the
     * SELECT ... FOR UPDATE SKIP LOCKED and the immediate status flip to
     * RUNNING happen atomically, so between them no other worker can see
     * this row as still QUEUED. This is the method a scheduled worker
     * calls on every poll tick.
     */
   @Transactional
    public Optional<PostureJob> claimNextJob() {
        Optional<PostureJob> claimed = jobRepository.findNextClaimable();

        claimed.ifPresent(job -> {
            job.setStatus(JobStatus.RUNNING);
            job.setStartedAt(Instant.now());
            job.setAttemptCount(job.getAttemptCount() + 1);
            jobRepository.save(job);

            // Force the lazy Endpoint proxy to resolve NOW, while the
            // transaction (and its Hibernate session) is still open.
            // JobWorker.runStubJob() reads job.getEndpoint().getMacAddress()
            // AFTER this method returns - by then the transaction is
            // closed, so without this line that read would throw
            // LazyInitializationException, same root cause as the one
            // JobController hit on GET /api/v1/jobs.
            job.getEndpoint().getMacAddress();
        });

        return claimed;
    }

    @Transactional
    public void markComplete(UUID jobId) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(JobStatus.COMPLETE);
            job.setCompletedAt(Instant.now());
            job.setErrorMessage(null);
            jobRepository.save(job);
        });
    }

    /**
     * Marks a job failed. If it still has attempts remaining, requeues it
     * with an exponential-ish backoff (attempt_count minutes, capped at
     * 15) rather than retrying immediately - the same spirit as the
     * Python project's HW_HEALTH_FAILURE_BACKOFF_SECONDS, so a target
     * that's genuinely unreachable doesn't get hammered every poll tick.
     * Once max_attempts is exhausted, the job is left in FAILED for a
     * human to see rather than retried forever.
     */
    @Transactional
    public void markFailed(UUID jobId, String errorMessage) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setErrorMessage(errorMessage);

            if (job.getAttemptCount() < job.getMaxAttempts()) {
                job.setStatus(JobStatus.QUEUED);
                long backoffMinutes = Math.min(job.getAttemptCount(), 15);
                job.setNextAttemptAt(Instant.now().plus(backoffMinutes, ChronoUnit.MINUTES));
            } else {
                job.setStatus(JobStatus.FAILED);
                job.setCompletedAt(Instant.now());
            }

            jobRepository.save(job);
        });
    }

    @Transactional(readOnly = true)
    public List<PostureJob> listAll() {
        return jobRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<PostureJob> listForEndpoint(UUID endpointId) {
        return jobRepository.findByEndpoint_IdOrderByCreatedAtDesc(endpointId);
    }
}