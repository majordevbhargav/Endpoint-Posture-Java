package com.endpointposture.posture;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;

/**
 * Data access for {@link Assessment}. Reads only ever add or list rows;
 * assessments are append-only history.
 */
public interface AssessmentRepository extends JpaRepository<Assessment, UUID> {

    /** @return that endpoint's assessments, newest first */
    List<Assessment> findByEndpointIdOrderByCreatedAtDesc(UUID endpointId);

    /**
     * @return its most recent assessment, or empty if it has never been assessed
     */
    Optional<Assessment> findFirstByEndpointIdOrderByCreatedAtDesc(UUID endpointId);

    /**
     * @return true if the endpoint has at least one assessment whose status is not
     *         the given one
     */
    boolean existsByEndpointIdAndStatusNot(UUID endpointId, AssessmentStatus status);

    /**
     * @return the newest assessment of every endpoint that has one (via
     *         endpoint.latest_assessment_id)
     */
    @Query(value = """
            SELECT a.* FROM assessment a
              JOIN endpoint e ON e.latest_assessment_id = a.id
            """, nativeQuery = true)
    List<Assessment> findLatestPerEndpoint();

    /**
     * Moves the endpoint's "latest" pointer forward; never backwards (a late, older
     * write cannot win).
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE endpoint
               SET latest_assessment_id = :id, latest_status = :status,
                   latest_assessed_at = CAST(:at AS timestamptz)
             WHERE id = :endpointId
               AND (latest_assessed_at IS NULL OR latest_assessed_at <= CAST(:at AS timestamptz))
            """, nativeQuery = true)
    int updateEndpointLatest(@org.springframework.data.repository.query.Param("endpointId") UUID endpointId,
            @org.springframework.data.repository.query.Param("id") UUID id,
            @org.springframework.data.repository.query.Param("status") String status,
            @org.springframework.data.repository.query.Param("at") java.time.Instant at);
        /** Latest assessment of the given endpoints, via endpoint.latest_assessment_id. */
    @Query(value = """
            SELECT a.* FROM assessment a
              JOIN endpoint e ON e.latest_assessment_id = a.id
             WHERE e.id IN (:ids)
            """, nativeQuery = true)
    List<Assessment> findLatestForEndpoints(
            @org.springframework.data.repository.query.Param("ids") java.util.Collection<UUID> ids);
}