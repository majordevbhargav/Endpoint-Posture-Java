package com.endpointposture.job;

import com.endpointposture.job.spec.DiagnosticSpec;
import com.endpointposture.job.spec.HardwareSpec;
import com.endpointposture.job.spec.JobSpec;
import com.endpointposture.job.spec.PostureSpec;
import com.endpointposture.job.spec.SecuritySpec;

import java.util.ArrayList;
import java.util.List;

/** Maps typed job semantics to the legacy PowerShell argv contract. */
final class AgentArgs {
    private AgentArgs() {}

    /** Arguments after "-File <script>". The URL remains runtime configuration, not part of the job spec. */
    static List<String> of(JobSpec spec, String serverUrl) {
        List<String> args = new ArrayList<>();
        switch (spec) {
            case PostureSpec s -> {
                add(args, "-ComputerName", s.target());
                add(args, "-JobId", s.jobId());
                add(args, "-PostureServer", serverUrl);
                add(args, "-CimOperationTimeoutSec", s.cimTimeoutSec());
                add(args, "-PostureServerTimeoutSec", s.serverTimeoutSec());
                add(args, "-PolicyVersion", s.policyVersion());
                if (!s.requiredApps().isEmpty()) add(args, "-RequiredAppsList", String.join("|", s.requiredApps()));
                if (!s.blockedApps().isEmpty()) add(args, "-BlockedAppsList", String.join("|", s.blockedApps()));
            }
            case HardwareSpec s -> {
                add(args, "-ComputerName", s.target());
                add(args, "-JobId", s.jobId());
                add(args, "-PostureAppBase", serverUrl);
                add(args, "-CimOperationTimeoutSec", s.cimTimeoutSec());
                add(args, "-SubmitTimeoutSec", s.submitTimeoutSec());
            }
            case DiagnosticSpec s -> {
                add(args, "-ComputerName", s.target());
                add(args, "-Mac", s.mac());
                add(args, "-JobId", s.jobId());
                add(args, "-PostureAppBase", serverUrl);
                add(args, "-WinRmOpenTimeoutSec", s.winrmOpenTimeoutSec());
                add(args, "-WinRmOperationTimeoutSec", s.winrmOperationTimeoutSec());
                add(args, "-SubmitTimeoutSec", s.submitTimeoutSec());
                add(args, "-DnsTestName", s.dnsTestName());
                add(args, "-InternetTarget", s.internetTarget());
            }
            case SecuritySpec s -> {
                add(args, "-ComputerName", s.target());
                add(args, "-Mac", s.mac());
                add(args, "-JobId", s.jobId());
                add(args, "-PostureAppBase", serverUrl);
                add(args, "-WinRmOpenTimeoutSec", s.winrmOpenTimeoutSec());
                add(args, "-WinRmOperationTimeoutSec", s.winrmOperationTimeoutSec());
                add(args, "-SubmitTimeoutSec", s.submitTimeoutSec());
                add(args, "-SampleCount", s.sampleCount());
                add(args, "-SampleIntervalSec", s.sampleIntervalSec());
            }
        }
        return List.copyOf(args);
    }

    private static void add(List<String> args, String name, Object value) {
        args.add(name);
        args.add(String.valueOf(value));
    }
}
