package com.endpointposture.workerruntime.execution;

import com.endpointposture.workerruntime.api.ClaimedJob;
import com.fasterxml.jackson.databind.JsonNode;

/** Immutable handoff snapshot for PR6.4. Contains the entire validated script envelope. */
public record CompletedJobResult(ClaimedJob claimedJob, ValidatedJob validatedJob,
                                 int processExitCode, JsonNode envelope,
                                 boolean stdoutTruncated, boolean stderrTruncated) {
    public CompletedJobResult {
        if (claimedJob == null || validatedJob == null || envelope == null || !envelope.isObject()) {
            throw new IllegalArgumentException("A completed result requires its claim, validated job, and structured envelope");
        }
        if (!claimedJob.jobId().equals(validatedJob.jobId())
                || !claimedJob.jobId().toString().equalsIgnoreCase(envelope.path("jobId").asText())
                || !validatedJob.jobType().equals(envelope.path("jobType").asText())) {
            throw new IllegalArgumentException("Handoff result identity does not match the claimed job");
        }
        envelope = envelope.deepCopy();
    }

    @Override public JsonNode envelope() { return envelope.deepCopy(); }
}