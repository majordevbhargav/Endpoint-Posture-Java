package com.endpointposture.job.spec;

import com.endpointposture.job.JobType;
import java.util.Objects;
import java.util.UUID;

public record SecuritySpec(UUID jobId, String target, String mac, int processTimeoutSeconds,
                           int winrmOpenTimeoutSec, int winrmOperationTimeoutSec, int submitTimeoutSec,
                           int sampleCount, int sampleIntervalSec) implements JobSpec {
    public SecuritySpec {
        Objects.requireNonNull(jobId, "jobId");
        target = SpecChecks.host(target, "target");
        mac = SpecChecks.mac(mac);
        SpecChecks.positive(processTimeoutSeconds, "processTimeoutSeconds");
        SpecChecks.positive(winrmOpenTimeoutSec, "winrmOpenTimeoutSec");
        SpecChecks.positive(winrmOperationTimeoutSec, "winrmOperationTimeoutSec");
        SpecChecks.positive(submitTimeoutSec, "submitTimeoutSec");
        SpecChecks.positive(sampleCount, "sampleCount");
        SpecChecks.positive(sampleIntervalSec, "sampleIntervalSec");
    }

    @Override public JobType jobType() { return JobType.SECURITY_CHECK; }
}
