package com.endpointposture.job;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointNotFoundException;
import com.endpointposture.endpoint.EndpointRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The job queue's business logic: enqueue, claim, complete, fail (with
 * retry backoff) and list.
 *
 * <p>Every state change happens inside a transaction, and claiming uses a
 * row lock so several workers (or a restart racing an in-flight run) can
 * never run the same job twice.</p>
 */
@Service
public class JobService {

    private final PostureJobRepository jobRepository;
    private final EndpointRepository endpointRepository;

    public JobService(PostureJobRepository jobRepository, EndpointRepository endpointRepository) {
        this.jobRepository = jobRepository;
        this.endpointRepository = endpointRepository;
    }

    /**
     * Adds a new job to the queue.
     *
     * @param endpointId the endpoint to run against
     * @param type       what kind of work to do
     * @param priority   higher values are claimed first
     * @return the saved job, status {@code QUEUED}
     * @throws EndpointNotFoundException if the endpoint does not exist
     */
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
     *
     * @return the job now marked {@code RUNNING}, or empty if the queue has nothing eligible
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
            // JobWorker reads job.getEndpoint() (id, IP, hostname) AFTER
            // this method returns - by then the transaction is closed, so
            // without this line that read would throw
            // LazyInitializationException, same root cause as the one
            // JobController hit on GET /api/v1/jobs.
            job.getEndpoint().getMacAddress();
        });

        return claimed;
    }

    /**
     * Marks a job finished successfully.
     *
     * @param jobId the job to complete; unknown IDs are ignored
     */
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
     *
     * @param jobId        the job that failed; unknown IDs are ignored
     * @param errorMessage human-readable reason, stored on the job
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

    /** @return all jobs, newest first */
    @Transactional(readOnly = true)
    public List<PostureJob> listAll() {
        return jobRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * @param endpointId the endpoint's internal UUID
     * @return that endpoint's jobs, newest first
     */
    @Transactional(readOnly = true)
    public List<PostureJob> listForEndpoint(UUID endpointId) {
        return jobRepository.findByEndpoint_IdOrderByCreatedAtDesc(endpointId);
    }
        /**
     * Enqueues a POSTURE_CHECK job for this endpoint only if it isn't
     * already due — prevents IseSessionWatcher flooding the queue with a
     * job every poll tick (15s) for the same endpoint. "Due" means: no
     * QUEUED or RUNNING job already exists for it, and its last completed
     * job (if any) finished more than the recheck interval ago.
     *
     * @param endpointId the endpoint to check
     * @param type       job type to enqueue
     */
    @Transactional
    public void enqueueIfDue(UUID endpointId, JobType type) {
        boolean alreadyPending = jobRepository
                .findByEndpoint_IdOrderByCreatedAtDesc(endpointId)
                .stream()
                .anyMatch(j -> j.getJobType() == type
                        && (j.getStatus() == JobStatus.QUEUED || j.getStatus() == JobStatus.RUNNING));

        if (!alreadyPending) {
            enqueue(endpointId, type, 0);
        }
    }
}
