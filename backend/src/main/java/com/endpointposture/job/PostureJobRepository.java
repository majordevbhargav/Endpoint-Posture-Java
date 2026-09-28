package com.endpointposture.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link PostureJob}, including the concurrency-safe claim query.
 */
public interface PostureJobRepository extends JpaRepository<PostureJob, UUID> {

    /**
     * Finds and locks the single highest-priority, oldest, currently-eligible
     * QUEUED job. FOR UPDATE SKIP LOCKED makes this safe under concurrency; it
     * must be called inside a @Transactional method (see JobService.claimNextJob()).
     */
    @Query(value = """
            SELECT * FROM posture_job
            WHERE status = 'QUEUED'
              AND (next_attempt_at IS NULL OR next_attempt_at <= now())
            ORDER BY priority DESC, created_at ASC
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<PostureJob> findNextClaimable();

    /** @return all jobs, newest first, with their endpoints loaded */
    @Query("SELECT j FROM PostureJob j JOIN FETCH j.endpoint ORDER BY j.createdAt DESC")
    List<PostureJob> findAllByOrderByCreatedAtDesc();

    /** @return that endpoint's jobs, newest first, with the endpoint loaded */
    @Query("SELECT j FROM PostureJob j JOIN FETCH j.endpoint WHERE j.endpoint.id = :endpointId ORDER BY j.createdAt DESC")
    List<PostureJob> findByEndpoint_IdOrderByCreatedAtDesc(@Param("endpointId") UUID endpointId);

    /** @return jobs still RUNNING that started before the cutoff (stalled), endpoints loaded */
    @Query("SELECT j FROM PostureJob j JOIN FETCH j.endpoint WHERE j.status = com.endpointposture.job.JobStatus.RUNNING AND j.startedAt < :cutoff")
    List<PostureJob> findStaleRunning(@Param("cutoff") Instant cutoff);

    /** @return true if a job of this type for this endpoint is in one of the given statuses */
    boolean existsByEndpoint_IdAndJobTypeAndStatusIn(UUID endpointId, JobType jobType, Collection<JobStatus> statuses);

    /** @return the most recent job of this type with this status (by completion time) */
    Optional<PostureJob> findFirstByEndpoint_IdAndJobTypeAndStatusOrderByCompletedAtDesc(
            UUID endpointId, JobType jobType, JobStatus status);

    /** @return the most recently created job of this type for this endpoint, any status */
    Optional<PostureJob> findFirstByEndpoint_IdAndJobTypeOrderByCreatedAtDesc(UUID endpointId, JobType jobType);
}