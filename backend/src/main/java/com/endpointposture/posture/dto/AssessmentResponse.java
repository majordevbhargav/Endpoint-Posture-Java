package com.endpointposture.posture.dto;

import com.endpointposture.posture.AssessmentStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API view of an {@link com.endpointposture.posture.Assessment} together
 * with its individual check results.
 *
 * @param id          the assessment's identifier
 * @param endpointId  the endpoint that was assessed
 * @param jobId       the job that triggered it, or {@code null}
 * @param status      overall result
 * @param detail      short human-readable summary
 * @param startedAt   when collection began
 * @param completedAt when the assessment was finished
 * @param checks      the individual check results
 */
public record AssessmentResponse(
        UUID id,
        UUID endpointId,
        UUID jobId,
        AssessmentStatus status,
        String detail,
        Instant startedAt,
        Instant completedAt,
        List<CheckResultResponse> checks
) {}
