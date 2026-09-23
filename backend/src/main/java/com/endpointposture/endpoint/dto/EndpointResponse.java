package com.endpointposture.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * API view of an {@link com.endpointposture.endpoint.Endpoint}. Only the
 * fields a dashboard needs; internal bookkeeping columns are not exposed.
 *
 * @param id          platform-internal identifier
 * @param macAddress  business key, always normalized uppercase
 * @param ipAddress   most recently observed IP
 * @param hostname    reported hostname, if known
 * @param osName      operating system name, if known
 * @param osVersion   operating system version, if known
 * @param connected   whether an ISE session is currently active
 * @param lastSeenAt  last time the endpoint was observed (UTC)
 */
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
