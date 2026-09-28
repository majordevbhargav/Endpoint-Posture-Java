package com.endpointposture.posture;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link Assessment}. Reads only ever add or list rows;
 * assessments are append-only history.
 */
public interface AssessmentRepository extends JpaRepository<Assessment, UUID> {

    /** @return that endpoint's assessments, newest first */
    List<Assessment> findByEndpointIdOrderByCreatedAtDesc(UUID endpointId);

    /** @return its most recent assessment, or empty if it has never been assessed */
    Optional<Assessment> findFirstByEndpointIdOrderByCreatedAtDesc(UUID endpointId);

    /** @return true if the endpoint has at least one assessment whose status is not the given one */
    boolean existsByEndpointIdAndStatusNot(UUID endpointId, AssessmentStatus status);

    /** @return the newest assessment for every endpoint that has at least one */
    @Query(value = """
            SELECT DISTINCT ON (endpoint_id) *
            FROM assessment
            ORDER BY endpoint_id, created_at DESC
            """, nativeQuery = true)
    List<Assessment> findLatestPerEndpoint();
}