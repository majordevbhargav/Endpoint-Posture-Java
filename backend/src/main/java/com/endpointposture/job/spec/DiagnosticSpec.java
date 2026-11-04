package com.endpointposture.job.spec;

import com.endpointposture.job.JobType;
import java.util.Objects;
import java.util.UUID;

public record DiagnosticSpec(UUID jobId, String target, String mac, int processTimeoutSeconds,
                             int winrmOpenTimeoutSec, int winrmOperationTimeoutSec, int submitTimeoutSec,
                             String dnsTestName, String internetTarget) implements JobSpec {
    public DiagnosticSpec {
        Objects.requireNonNull(jobId, "jobId");
        target = SpecChecks.host(target, "target");
        mac = SpecChecks.mac(mac);
        SpecChecks.positive(processTimeoutSeconds, "processTimeoutSeconds");
        SpecChecks.positive(winrmOpenTimeoutSec, "winrmOpenTimeoutSec");
        SpecChecks.positive(winrmOperationTimeoutSec, "winrmOperationTimeoutSec");
        SpecChecks.positive(submitTimeoutSec, "submitTimeoutSec");
        dnsTestName = SpecChecks.host(dnsTestName, "dnsTestName");
        internetTarget = SpecChecks.host(internetTarget, "internetTarget");
    }

    @Override public JobType jobType() { return JobType.DIAGNOSTIC_CHECK; }
}
