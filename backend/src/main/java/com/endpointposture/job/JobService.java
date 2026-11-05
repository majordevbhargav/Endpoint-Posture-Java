package com.endpointposture.job;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointNotFoundException;
import com.endpointposture.endpoint.EndpointRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
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
 * <p>
 * Every state change happens inside a transaction, and claiming uses a
 * row lock so several workers can never run the same job twice.
 * </p>
 *
 * <p>
 * Priority convention: manual jobs use 10, automatic ones 0.
 * </p>
 */
@Service
public class JobService {

    /**
     * Default minimum gap between a completed check and a reconnect-triggered one.
     */
    static final Duration DEFAULT_RECONNECT_MIN_GAP = Duration.ofMinutes(60);

    private final PostureJobRepository jobRepository;
    private final EndpointRepository endpointRepository;
    private final Duration reconnectMinGap;

    /**
     * Uses {@link #DEFAULT_RECONNECT_MIN_GAP}. Kept so existing tests can build the
     * service directly.
     */
    public JobService(PostureJobRepository jobRepository, EndpointRepository endpointRepository) {
        this(jobRepository, endpointRepository, DEFAULT_RECONNECT_MIN_GAP);
    }

    /**
     * Constructor used by Spring.
     *
     * @param reconnectMinGapMinutes {@code app.jobs.reconnect-min-gap-minutes}; a
     *                               reconnect does
     *                               not queue a new check if the last one completed
     *                               within this
     *                               many minutes. {@code 0} disables the gap.
     */
    @Autowired
    public JobService(PostureJobRepository jobRepository,
            EndpointRepository endpointRepository,
            @Value("${app.jobs.reconnect-min-gap-minutes:60}") long reconnectMinGapMinutes) {
        this(jobRepository, endpointRepository, Duration.ofMinutes(Math.max(0, reconnectMinGapMinutes)));
    }

    private JobService(PostureJobRepository jobRepository,
            EndpointRepository endpointRepository,
            Duration reconnectMinGap) {
        this.jobRepository = jobRepository;
        this.endpointRepository = endpointRepository;
        this.reconnectMinGap = reconnectMinGap;
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
     * Queues one job of this type for every connected endpoint that has a target and nothing pending.
     */
    @Transactional
    public int enqueueForConnected(JobType type, int priority) {
        return jobRepository.enqueueForConnected(type.name(), priority);
    }

    /**
     * Claims the next eligible job for the local in-process worker pool.
     * Local jobs do not hold a remote-worker lease.
     *
     * @return the job now marked RUNNING, or empty if nothing is eligible
     */
    @Transactional
    public Optional<PostureJob> claimNextJob() {
        return claim(null, null, null);
    }

    /**
     * Claims the next eligible job for a remote worker and records its lease.
     * The row lock acquired by {@code findNextClaimable()} is held until this
     * transaction commits, making the status change and lease assignment atomic.
     * The incremented {@code attemptCount} is the fencing token for this attempt;
     * remote completion and failure validate that token and the active lease.
     *
     * @param workerId stable identity of the claiming worker; must not be blank
     * @param lease    how long the lease lasts from now; must be positive
     * @return the leased job, or empty if no job is eligible
     * @throws IllegalArgumentException if the worker ID is blank or the lease is not positive
     */
    @Transactional
    public Optional<PostureJob> claimNextJob(String workerId, Duration lease) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId is required");
        }
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        return claim(workerId.trim(), lease, null);
    }

    /** Claims only jobs assigned to the worker's authenticated shard. */
    @Transactional
    public Optional<PostureJob> claimNextJobInShard(String workerId, String shard, Duration lease) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId is required");
        }
        if (shard == null || shard.isBlank()) {
            throw new IllegalArgumentException("shard is required");
        }
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        return claim(workerId.trim(), lease, shard.trim());
    }

    private Optional<PostureJob> claim(String workerId, Duration lease, String shard) {
        Optional<PostureJob> claimed = shard == null
                ? jobRepository.findNextClaimable()
                : jobRepository.findNextClaimableInShard(shard);

        claimed.ifPresent(job -> {
            Instant now = Instant.now();
            job.setStatus(JobStatus.RUNNING);
            job.setStartedAt(now);
            job.setAttemptCount(job.getAttemptCount() + 1);
            job.setClaimedBy(workerId);
            job.setLeaseExpiresAt(lease == null ? null : now.plus(lease));
            jobRepository.save(job);

            // Resolve the lazy Endpoint while the transaction is open. JobWorker
            // reads the endpoint after this method returns.
            job.getEndpoint().getMacAddress();
        });

        return claimed;
    }

    /**
     * Completes a remote-worker attempt only while the submitting worker still
     * owns the current, unexpired lease. The repository enforces all ownership
     * and fencing predicates in one atomic UPDATE.
     *
     * @return true when this attempt completed the job; false when it is stale,
     *         expired, superseded, or the job is no longer RUNNING
     */
    @Transactional
    public boolean completeFenced(UUID jobId, String workerId, int attempt) {
        validateFencedAttempt(jobId, workerId, attempt);
        return jobRepository.completeFenced(jobId, workerId.trim(), attempt) == 1;
    }

    /**
     * Records a remote-worker failure only while the worker still owns the
     * current, unexpired lease. Accepted failures follow the existing retry
     * policy and release the lease before the job can be claimed again.
     *
     * @return true when this attempt's failure was accepted; false when stale,
     *         expired, superseded, or no longer RUNNING
     */
    @Transactional
    public boolean failFenced(UUID jobId, String workerId, int attempt, String errorMessage) {
        validateFencedAttempt(jobId, workerId, attempt);
        return jobRepository.failFenced(jobId, workerId.trim(), attempt, errorMessage) == 1;
    }

    /**
     * Extends a remote worker's lease by {@code extension} from now. Only the
     * owning worker, for the current attempt, while the lease is unexpired.
     *
     * @return the new lease expiry, or empty if the attempt is stale, not owned,
     *         expired or no longer RUNNING
     */
    @Transactional
    public Optional<Instant> renewLease(UUID jobId, String workerId, int attempt, Duration extension) {
        validateFencedAttempt(jobId, workerId, attempt);
        if (extension == null || extension.isZero() || extension.isNegative()) {
            throw new IllegalArgumentException("extension must be positive");
        }
        if (jobRepository.renewLease(jobId, workerId.trim(), attempt, extension.toSeconds()) != 1) {
            return Optional.empty();
        }
        return jobRepository.findById(jobId).map(PostureJob::getLeaseExpiresAt);
    }

    /** Stale-sweep failure for a remote-leased attempt; only while the lease is expired. */
    @Transactional
    public boolean failExpiredLeaseAttempt(UUID jobId, int attempt, String reason) {
        if (jobId == null || attempt < 1) {
            throw new IllegalArgumentException("jobId and a positive attempt are required");
        }
        return jobRepository.failExpiredLeaseAttempt(jobId, attempt, reason) == 1;
    }

    private void validateFencedAttempt(UUID jobId, String workerId, int attempt) {
        if (jobId == null) {
            throw new IllegalArgumentException("jobId is required");
        }
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId is required");
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
    }

    /**
     * Completes an embedded-worker attempt only if it is still the current
     * RUNNING attempt. Local claims have no remote worker identity or lease.
     */
    @Transactional
    public boolean completeAttempt(UUID jobId, int attempt) {
        if (jobId == null || attempt < 1) {
            throw new IllegalArgumentException("jobId and a positive attempt are required");
        }
        return jobRepository.completeRunningAttempt(jobId, attempt) == 1;
    }

    /**
     * Fails an embedded-worker or stale-sweep attempt only if it is still the
     * current RUNNING attempt. Remote workers must use failFenced instead.
     */
    @Transactional
    public boolean failAttempt(UUID jobId, int attempt, String reason) {
        if (jobId == null || attempt < 1) {
            throw new IllegalArgumentException("jobId and a positive attempt are required");
        }
        return jobRepository.failRunningAttempt(jobId, attempt, reason) == 1;
    }

    /** Marks a job finished successfully. Unknown IDs are ignored. */
    @Transactional
    public void markComplete(UUID jobId) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(JobStatus.COMPLETE);
            job.setCompletedAt(Instant.now());
            job.setErrorMessage(null);
            job.setClaimedBy(null);
            job.setLeaseExpiresAt(null);
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
                job.setCompletedAt(null);
            } else {
                job.setStatus(JobStatus.FAILED);
                job.setNextAttemptAt(null);
                job.setCompletedAt(Instant.now());
            }

            job.setClaimedBy(null);
            job.setLeaseExpiresAt(null);
            jobRepository.save(job);
        });
    }

    /**
     * @param limit how many jobs to return; the caller is responsible for keeping
     *              it sensible
     * @return the newest jobs first, at most {@code limit} of them
     */
    @Transactional(readOnly = true)
    public List<PostureJob> listRecent(int limit) {
        return jobRepository.findRecent(PageRequest.of(0, limit));
    }

    /**
     * @param endpointId the endpoint whose jobs to list
     * @param limit      how many jobs to return
     * @return that endpoint's newest jobs first, at most {@code limit} of them
     */
    @Transactional(readOnly = true)
    public List<PostureJob> listForEndpoint(UUID endpointId, int limit) {
        return jobRepository.findRecentForEndpoint(endpointId, PageRequest.of(0, limit));
    }

    /**
     * Reconnect path: enqueues a job unless one of this type is already QUEUED or
     * RUNNING for the endpoint, or the last one COMPLETED within the reconnect
     * minimum gap ({@code app.jobs.reconnect-min-gap-minutes}, default 60).
     *
     * <p>
     * Without the gap, every device reconnecting each morning would queue a
     * fresh check even if it was checked minutes earlier. Prevents the session
     * watcher flooding the queue. Regular timed rechecks are decided by
     * {@link #enqueueIfDue(UUID, JobType, Duration, Duration)}.
     * </p>
     */
    @Transactional
    public void enqueueIfDue(UUID endpointId, JobType type) {
        boolean alreadyPending = jobRepository.existsByEndpoint_IdAndJobTypeAndStatusIn(
                endpointId, type, List.of(JobStatus.QUEUED, JobStatus.RUNNING));
        if (alreadyPending) {
            return;
        }

        if (!reconnectMinGap.isZero()) {
            Instant tooRecentAfter = Instant.now().minus(reconnectMinGap);
            boolean checkedRecently = jobRepository
                    .findFirstByEndpoint_IdAndJobTypeAndStatusOrderByCompletedAtDesc(endpointId, type,
                            JobStatus.COMPLETE)
                    .map(PostureJob::getCompletedAt)
                    .map(done -> done.isAfter(tooRecentAfter))
                    .orElse(false);
            if (checkedRecently) {
                return;
            }
        }

        enqueue(endpointId, type, 0);
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