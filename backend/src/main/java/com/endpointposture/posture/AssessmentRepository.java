package com.endpointposture.posture;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link Assessment}. Reads only ever add or list rows;
 * assessments are append-only history.
 */
public interface AssessmentRepository extends JpaRepository<Assessment, UUID> {

    /**
     * @param endpointId the endpoint's internal UUID
     * @return that endpoint's assessments, newest first
     */
    List<Assessment> findByEndpointIdOrderByCreatedAtDesc(UUID endpointId);

    /**
     * @param endpointId the endpoint's internal UUID
     * @return its most recent assessment, or empty if it has never been assessed
     */
    Optional<Assessment> findFirstByEndpointIdOrderByCreatedAtDesc(UUID endpointId);
}
