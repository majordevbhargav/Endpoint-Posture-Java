package com.endpointposture.job.spec;

import com.endpointposture.job.JobType;
import java.util.UUID;

/** A validated, typed description of one agent run. Contains no command text. */
public sealed interface JobSpec permits PostureSpec, HardwareSpec, DiagnosticSpec, SecuritySpec {
    UUID jobId();
    String target();
    int processTimeoutSeconds();
    JobType jobType();
}
