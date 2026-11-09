package com.endpointposture.workerruntime.execution;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Worker-side allowlisted job representation; contains arguments, never PowerShell source text. */
public record ValidatedJob(UUID jobId, int attempt, String jobType, String scriptName,
                           List<String> arguments, Duration processTimeout) {
    public ValidatedJob {
        arguments = List.copyOf(arguments);
    }
}