package com.endpointposture.workerruntime.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** A claimed job. The spec stays raw JSON until PR6.3 maps it to agent arguments. */
public record ClaimedJob(UUID jobId, int attempt, String jobType, JsonNode spec, Instant leaseExpiresAt) {}