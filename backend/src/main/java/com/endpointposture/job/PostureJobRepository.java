package com.endpointposture.job;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link PostureJob}, including the concurrency-safe claim
 * query.
 *
 * <p>
 * Listing queries are always bounded by a {@link Pageable}: the job table
 * grows without limit between retention runs, so nothing here returns "every
 * job".
 * </p>
 */
public interface PostureJobRepository extends JpaRepository<PostureJob, UUID> {

        /**
         * Finds and locks the single highest-priority, oldest, currently-eligible
         * QUEUED job. FOR UPDATE SKIP LOCKED makes this safe under concurrency; it
         * must be called inside a @Transactional method (see
         * JobService.claimNextJob()).
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

        /**
         * @param pageable page request, normally {@code PageRequest.of(0, limit)}
         * @return the newest jobs first, with their endpoints loaded
         */
        @Query("SELECT j FROM PostureJob j JOIN FETCH j.endpoint ORDER BY j.createdAt DESC")
        List<PostureJob> findRecent(Pageable pageable);

        /**
         * @param endpointId the endpoint whose jobs to list
         * @param pageable   page request, normally {@code PageRequest.of(0, limit)}
         * @return that endpoint's newest jobs first, with the endpoint loaded
         */
        @Query("SELECT j FROM PostureJob j JOIN FETCH j.endpoint WHERE j.endpoint.id = :endpointId ORDER BY j.createdAt DESC")
        List<PostureJob> findRecentForEndpoint(@Param("endpointId") UUID endpointId, Pageable pageable);

        /**
         * @return jobs still RUNNING that started before the cutoff (stalled),
         *         endpoints loaded
         */
        @Query("SELECT j FROM PostureJob j JOIN FETCH j.endpoint WHERE j.status = com.endpointposture.job.JobStatus.RUNNING AND j.startedAt < :cutoff")
        List<PostureJob> findStaleRunning(@Param("cutoff") Instant cutoff);

        /**
         * Bulk-deletes finished jobs that completed before the cutoff. Evidence rows
         * (assessments, hardware, diagnostics, indicators) keep existing: their
         * {@code job_id} foreign keys are {@code ON DELETE SET NULL}.
         *
         * @param cutoff   jobs completed strictly before this instant are deleted
         * @param statuses which statuses may be deleted (callers pass only finished
         *                 ones)
         * @return how many rows were deleted
         */
        @Modifying
        @Query("DELETE FROM PostureJob j WHERE j.status IN :statuses AND j.completedAt < :cutoff")
        int deleteFinishedBefore(@Param("cutoff") Instant cutoff, @Param("statuses") Collection<JobStatus> statuses);

        /**
         * @return true if a job of this type for this endpoint is in one of the given
         *         statuses
         */
        boolean existsByEndpoint_IdAndJobTypeAndStatusIn(UUID endpointId, JobType jobType,
                        Collection<JobStatus> statuses);

        /**
         * @return the most recent job of this type with this status (by completion
         *         time)
         */
        Optional<PostureJob> findFirstByEndpoint_IdAndJobTypeAndStatusOrderByCompletedAtDesc(
                        UUID endpointId, JobType jobType, JobStatus status);

        /**
         * @return the most recently created job of this type for this endpoint, any
         *         status
         */
        Optional<PostureJob> findFirstByEndpoint_IdAndJobTypeOrderByCreatedAtDesc(UUID endpointId, JobType jobType);

        /** @return how many jobs are in this status */
        long countByStatus(JobStatus status);

        /** @return the oldest job in this status, by creation time */
        Optional<PostureJob> findFirstByStatusOrderByCreatedAtAsc(JobStatus status);

        /** @return how many jobs in this status completed after the given instant */
        long countByStatusAndCompletedAtAfter(JobStatus status, Instant after);

        /**
         * One INSERT ... SELECT: a job of this type for every connected endpoint with a
         * target and none pending.
         */
        @Modifying
        @Query(value = """
                        INSERT INTO posture_job (endpoint_id, job_type, status, priority, attempt_count, max_attempts)
                        SELECT e.id, CAST(:type AS text), 'QUEUED', CAST(:priority AS integer), 0, 3
                          FROM endpoint e
                         WHERE e.connected
                           AND (COALESCE(e.ip_address, '') <> '' OR COALESCE(e.hostname, '') <> '')
                           AND NOT EXISTS (SELECT 1 FROM posture_job j
                                            WHERE j.endpoint_id = e.id AND j.job_type = CAST(:type AS text)
                                              AND j.status IN ('QUEUED', 'RUNNING'))
                        """, nativeQuery = true)
        int enqueueForConnected(@Param("type") String type, @Param("priority") int priority);
}