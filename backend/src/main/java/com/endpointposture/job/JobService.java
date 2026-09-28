package com.endpointposture.job;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointNotFoundException;
import com.endpointposture.endpoint.EndpointRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The job queue's business logic: enqueue, claim, complete, fail (with
 * retry backoff), recovery, scheduled-recheck decisions and listing.
 *
 * <p>Every state change happens inside a transaction, and claiming uses a
 * row lock so several workers can never run the same job twice.</p>
 *
 * <p>Priority convention: manual jobs use 10, automatic ones 0.</p>
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
     * Claims and locks the next eligible job in one transaction (SELECT ... FOR
     * UPDATE SKIP LOCKED, then flip to RUNNING).
     *
     * @return the job now marked RUNNING, or empty if nothing is eligible
     */
    @Transactional
    public Optional<PostureJob> claimNextJob() {
        Optional<PostureJob> claimed = jobRepository.findNextClaimable();

        claimed.ifPresent(job -> {
            job.setStatus(JobStatus.RUNNING);
            job.setStartedAt(Instant.now());
            job.setAttemptCount(job.getAttemptCount() + 1);
            jobRepository.save(job);

            // Force the lazy Endpoint proxy to resolve NOW, while the transaction is
            // open; JobWorker reads job.getEndpoint() after this method returns.
            job.getEndpoint().getMacAddress();
        });

        return claimed;
    }

    /** Marks a job finished successfully. Unknown IDs are ignored. */
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
     * Marks a job failed. If attempts remain it is requeued with backoff
     * (attempt_count minutes, capped at 15); otherwise it is left FAILED.
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

    /**
     * Like {@link #markFailed} but only acts if the job is still RUNNING, so a
     * job that finished in the meantime is never flipped back to QUEUED.
     *
     * @return true if the job was still RUNNING and has now been failed/requeued
     */
    @Transactional
    public boolean markFailedIfRunning(UUID jobId, String errorMessage) {
        boolean running = jobRepository.findById(jobId)
                .map(j -> j.getStatus() == JobStatus.RUNNING).orElse(false);
        if (!running) return false;
        markFailed(jobId, errorMessage);
        return true;
    }

    /** @return all jobs, newest first */
    @Transactional(readOnly = true)
    public List<PostureJob> listAll() {
        return jobRepository.findAllByOrderByCreatedAtDesc();
    }

    /** @return that endpoint's jobs, newest first */
    @Transactional(readOnly = true)
    public List<PostureJob> listForEndpoint(UUID endpointId) {
        return jobRepository.findByEndpoint_IdOrderByCreatedAtDesc(endpointId);
    }

    /**
     * Enqueues a job unless one of this type is already QUEUED or RUNNING for the
     * endpoint. No interval check: used on reconnect, where a fresh check is wanted
     * immediately. Prevents the session watcher flooding the queue every poll tick.
     */
    @Transactional
    public void enqueueIfDue(UUID endpointId, JobType type) {
        boolean alreadyPending = jobRepository.existsByEndpoint_IdAndJobTypeAndStatusIn(
                endpointId, type, List.of(JobStatus.QUEUED, JobStatus.RUNNING));
        if (!alreadyPending) {
            enqueue(endpointId, type, 0);
        }
    }

    /**
     * Timed-recheck decision: enqueues an automatic (priority 0) job only if
     * (a) none of this type is QUEUED/RUNNING, (b) the last COMPLETE one finished
     * more than {@code interval} ago (or never ran), and (c) the most recent job of
     * this type did not FAIL within {@code failureBackoff}. Everything is derived
     * from the database, so it survives restarts.
     *
     * @return true if a job was enqueued
     */
    @Transactional
    public boolean enqueueIfDue(UUID endpointId, JobType type, Duration interval, Duration failureBackoff) {
        if (jobRepository.existsByEndpoint_IdAndJobTypeAndStatusIn(
                endpointId, type, List.of(JobStatus.QUEUED, JobStatus.RUNNING))) {
            return false;
        }

        Instant now = Instant.now();

        Optional<PostureJob> lastComplete = jobRepository
                .findFirstByEndpoint_IdAndJobTypeAndStatusOrderByCompletedAtDesc(endpointId, type, JobStatus.COMPLETE);
        if (lastComplete.isPresent() && lastComplete.get().getCompletedAt() != null
                && lastComplete.get().getCompletedAt().isAfter(now.minus(interval))) {
            return false; // checked recently enough
        }

        Optional<PostureJob> last = jobRepository.findFirstByEndpoint_IdAndJobTypeOrderByCreatedAtDesc(endpointId, type);
        if (last.isPresent() && last.get().getStatus() == JobStatus.FAILED
                && last.get().getCompletedAt() != null
                && last.get().getCompletedAt().isAfter(now.minus(failureBackoff))) {
            return false; // failed recently: do not hammer an unreachable device
        }

        enqueue(endpointId, type, 0);
        return true;
    }
}