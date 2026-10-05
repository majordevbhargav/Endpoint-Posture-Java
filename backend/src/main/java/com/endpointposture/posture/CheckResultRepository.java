package com.endpointposture.posture;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
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

    /**
     * Batch form of {@link #findByAssessmentId}: one query for many assessments,
     * which removes the one-query-per-assessment (N+1) pattern.
     * Keep the collection under about 1000 ids per call (see {@code AssessmentService}).
     *
     * @param assessmentIds the parent assessments
     * @return all checks recorded under any of them, in no particular order
     */
    List<CheckResult> findByAssessmentIdIn(Collection<UUID> assessmentIds);
}