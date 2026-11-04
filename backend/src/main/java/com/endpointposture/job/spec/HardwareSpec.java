package com.endpointposture.job.spec;

import com.endpointposture.job.JobType;
import java.util.Objects;
import java.util.UUID;

public record HardwareSpec(UUID jobId, String target, int processTimeoutSeconds,
                           int cimTimeoutSec, int submitTimeoutSec) implements JobSpec {
    public HardwareSpec {
        Objects.requireNonNull(jobId, "jobId");
        target = SpecChecks.host(target, "target");
        SpecChecks.positive(processTimeoutSeconds, "processTimeoutSeconds");
        SpecChecks.positive(cimTimeoutSec, "cimTimeoutSec");
        SpecChecks.positive(submitTimeoutSec, "submitTimeoutSec");
    }

    @Override public JobType jobType() { return JobType.HARDWARE_CHECK; }
}
