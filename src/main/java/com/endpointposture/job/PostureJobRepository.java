package com.endpointposture.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PostureJobRepository extends JpaRepository<PostureJob, UUID> {

    /**
     * Finds and locks the single highest-priority, oldest, currently-
     * eligible QUEUED job - FOR UPDATE SKIP LOCKED is what makes this
     * safe under concurrency (see JobService.claimNextJob() for why this
     * must be called inside a @Transactional method).
     *
     * This is native SQL, so JOIN FETCH (a JPQL-only concept) can't be
     * used here. That's why JobService.claimNextJob() explicitly touches
     * job.getEndpoint() before its transaction ends - see the comment
     * there for why that one line matters.
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
     * JOIN FETCH pulls the related Endpoint back in the SAME query,
     * instead of leaving it as a lazy proxy that would throw
     * LazyInitializationException the moment JobController touches
     * job.getEndpoint() after this method's transaction has closed.
     */
    @Query("SELECT j FROM PostureJob j JOIN FETCH j.endpoint ORDER BY j.createdAt DESC")
    List<PostureJob> findAllByOrderByCreatedAtDesc();

    @Query("SELECT j FROM PostureJob j JOIN FETCH j.endpoint WHERE j.endpoint.id = :endpointId ORDER BY j.createdAt DESC")
    List<PostureJob> findByEndpoint_IdOrderByCreatedAtDesc(@Param("endpointId") UUID endpointId);
}