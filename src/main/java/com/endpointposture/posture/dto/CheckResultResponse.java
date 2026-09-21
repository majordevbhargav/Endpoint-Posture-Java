package com.endpointposture.posture.dto;

import com.endpointposture.posture.AssessmentStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * API view of a {@link com.endpointposture.posture.CheckResult}.
 *
 * @param id        the check result's identifier
 * @param checkType kind of check, for example {@code FIREWALL}
 * @param status    result of this check
 * @param details   raw facts behind the result
 * @param createdAt when the row was recorded
 */
public record CheckResultResponse(
        UUID id,
        String checkType,
        AssessmentStatus status,
        Map<String, Object> details,
        Instant createdAt
) {}
