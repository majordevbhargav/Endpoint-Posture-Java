package com.endpointposture.posture;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Data access for {@link CheckResult}.
 */
public interface CheckResultRepository extends JpaRepository<CheckResult, UUID> {

    /**
     * @param assessmentId the parent assessment
     * @return all checks recorded under it
     */
    List<CheckResult> findByAssessmentId(UUID assessmentId);
}
