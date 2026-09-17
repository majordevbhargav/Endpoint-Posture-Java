package com.endpointposture.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A device this platform has discovered, with its latest known posture-adjacent identity fields.")
public record EndpointResponse(

        @Schema(description = "Platform-internal identifier. Stable even if the device's IP changes.")
        UUID id,

        @Schema(description = "The device's MAC address - the real business key used for ingestion/lookup, always uppercase.", example = "AA:BB:CC:DD:EE:FF")
        String macAddress,

        @Schema(description = "Most recently observed IP address.", example = "10.25.1.114")
        String ipAddress,

        @Schema(description = "Reported hostname, if known.")
        String hostname,

        @Schema(description = "Operating system name, if known.")
        String osName,

        @Schema(description = "Operating system version string, if known.")
        String osVersion,

        @Schema(description = "Whether this endpoint currently has an active ISE session. Independent of posture status - a device can be connected with stale posture, or disconnected with a recent good result.")
        boolean connected,

        @Schema(description = "Timestamp this endpoint was last observed, in UTC.")
        Instant lastSeenAt
) {}