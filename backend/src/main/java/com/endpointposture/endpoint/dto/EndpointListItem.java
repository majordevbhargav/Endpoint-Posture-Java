package com.endpointposture.endpoint.dto;

import java.time.Instant;
import java.util.UUID;

/** A row of the paged endpoint list: identity plus the latest posture status, in one query. */
public record EndpointListItem(UUID id, String macAddress, String ipAddress, String hostname,
                               String osName, String osVersion, boolean connected, Instant lastSeenAt,
                               String postureStatus, Instant postureAt) {}