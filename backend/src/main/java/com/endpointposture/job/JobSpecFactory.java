package com.endpointposture.job;

import com.endpointposture.diagnostic.config.DiagnosticAgentProperties;
import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.hardware.config.HardwareAgentProperties;
import com.endpointposture.indicator.config.SecurityIndicatorAgentProperties;
import com.endpointposture.job.spec.DiagnosticSpec;
import com.endpointposture.job.spec.HardwareSpec;
import com.endpointposture.job.spec.JobSpec;
import com.endpointposture.job.spec.PostureSpec;
import com.endpointposture.job.spec.SecuritySpec;
import com.endpointposture.policy.PolicyService;
import com.endpointposture.posture.config.PostureAgentProperties;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Builds validated, typed descriptions of agent runs from jobs and current configuration. */
@Component
public class JobSpecFactory {
    private final PostureAgentProperties posture;
    private final HardwareAgentProperties hardware;
    private final DiagnosticAgentProperties diagnostic;
    private final SecurityIndicatorAgentProperties security;
    private final PolicyService policyService;

    public JobSpecFactory(PostureAgentProperties posture, HardwareAgentProperties hardware,
                          DiagnosticAgentProperties diagnostic, SecurityIndicatorAgentProperties security,
                          PolicyService policyService) {
        this.posture = posture;
        this.hardware = hardware;
        this.diagnostic = diagnostic;
        this.security = security;
        this.policyService = policyService;
    }

    public JobSpec build(PostureJob job, Endpoint endpoint) {
        String target = targetFor(endpoint);
        UUID id = job.getId();
        return switch (job.getJobType()) {
            case POSTURE_CHECK -> {
                PolicyService.PolicySnapshot policy = policyService.getActive();
                yield new PostureSpec(id, target, posture.getProcessTimeoutSeconds(),
                        posture.getCimTimeoutSeconds(), posture.getServerTimeoutSeconds(),
                        policy.version(), policy.requiredApps(), policy.blockedApps());
            }
            case HARDWARE_CHECK -> new HardwareSpec(id, target, hardware.getProcessTimeoutSeconds(),
                    hardware.getCimTimeoutSeconds(), hardware.getSubmitTimeoutSeconds());
            case DIAGNOSTIC_CHECK -> new DiagnosticSpec(id, target, endpoint.getMacAddress(),
                    diagnostic.getProcessTimeoutSeconds(), diagnostic.getWinrmOpenTimeoutSeconds(),
                    diagnostic.getWinrmOperationTimeoutSeconds(), diagnostic.getSubmitTimeoutSeconds(),
                    diagnostic.getDnsTestName(), diagnostic.getInternetTarget());
            case SECURITY_CHECK -> new SecuritySpec(id, target, endpoint.getMacAddress(),
                    security.getProcessTimeoutSeconds(), security.getWinrmOpenTimeoutSeconds(),
                    security.getWinrmOperationTimeoutSeconds(), security.getSubmitTimeoutSeconds(),
                    security.getSampleCount(), security.getSampleIntervalSeconds());
        };
    }

    private static String targetFor(Endpoint endpoint) {
        if (endpoint.getIpAddress() != null && !endpoint.getIpAddress().isBlank()) return endpoint.getIpAddress();
        if (endpoint.getHostname() != null && !endpoint.getHostname().isBlank()) return endpoint.getHostname();
        throw new IllegalStateException("Endpoint " + endpoint.getMacAddress()
                + " has neither an IP address nor a hostname");
    }
}
