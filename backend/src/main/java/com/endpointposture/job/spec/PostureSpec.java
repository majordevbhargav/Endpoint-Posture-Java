package com.endpointposture.job.spec;

import com.endpointposture.job.JobType;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record PostureSpec(UUID jobId, String target, int processTimeoutSeconds,
                          int cimTimeoutSec, int serverTimeoutSec, int policyVersion,
                          List<String> requiredApps, List<String> blockedApps) implements JobSpec {
    public PostureSpec {
        Objects.requireNonNull(jobId, "jobId");
        target = SpecChecks.host(target, "target");
        SpecChecks.positive(processTimeoutSeconds, "processTimeoutSeconds");
        SpecChecks.positive(cimTimeoutSec, "cimTimeoutSec");
        SpecChecks.positive(serverTimeoutSec, "serverTimeoutSec");
        SpecChecks.positive(policyVersion, "policyVersion");
        requiredApps = SpecChecks.patterns(requiredApps);
        blockedApps = SpecChecks.patterns(blockedApps);
    }

    @Override public JobType jobType() { return JobType.POSTURE_CHECK; }
}
