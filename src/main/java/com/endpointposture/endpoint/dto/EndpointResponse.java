package com.endpointposture.endpoint.dto;

import java.time.Instant;
import java.util.UUID;

public record EndpointResponse(
        UUID id,
        String macAddress,
        String ipAddress,
        String hostname,
        String osName,
        String osVersion,
        boolean connected,
        Instant lastSeenAt
) {}