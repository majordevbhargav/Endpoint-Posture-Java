package com.endpointposture.ise.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Body of {@code POST /api/v1/ise/posture/share}. */
public record ShareRequest(@NotNull UUID endpointId) {}