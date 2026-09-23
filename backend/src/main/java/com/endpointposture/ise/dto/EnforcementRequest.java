package com.endpointposture.ise.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Body of {@code POST /api/v1/ise/enforcement/restrict} and
 * {@code /api/v1/ise/enforcement/clear}.
 *
 * @param endpointId the endpoint to act on
 * @param policy     optional ANC policy name override; ignored entirely
 *                   under {@code app.ise.enforcement-mode: ATTRIBUTE} and
 *                   on a clear request
 */
public record EnforcementRequest(@NotNull UUID endpointId, String policy) {}