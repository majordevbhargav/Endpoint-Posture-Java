package com.endpointposture.posture.dto;

import com.endpointposture.posture.AssessmentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * One check result as submitted by an agent. Used both inside the incoming
 * {@link PostureReportRequest} and as the input to
 * {@link com.endpointposture.posture.AssessmentService#recordAssessment}.
 *
 * @param checkType kind of check, for example {@code FIREWALL}
 * @param status    result of this check
 * @param details   raw facts behind the result, stored as JSONB; may be {@code null}
 */
public record CheckInput(
        @NotBlank String checkType,
        @NotNull AssessmentStatus status,
        Map<String, Object> details
) {}
