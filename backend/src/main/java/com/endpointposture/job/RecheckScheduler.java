package com.endpointposture.job;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointRepository;
import com.endpointposture.posture.AssessmentRepository;
import com.endpointposture.posture.AssessmentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Timed rechecks. Every sweep, for each CONNECTED endpoint that has an IP or
 * hostname, it asks {@link JobService#enqueueIfDue} to queue an automatic
 * (priority 0) posture check and, once the endpoint has answered at least one
 * posture check, a hardware check.
 *
 * <p>Disconnected endpoints are never auto-queued; they keep their last data.
 * This class only enqueues jobs. It never calls Cisco ISE.</p>
 */
@Component
@ConditionalOnProperty(name = "app.jobs.recheck.enabled", havingValue = "true", matchIfMissing = true)
public class RecheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecheckScheduler.class);

    private final EndpointRepository endpoints;
    private final AssessmentRepository assessments;
    private final JobService jobService;
    private final Duration postureInterval;
    private final Duration hardwareInterval;
    private final Duration failureBackoff;

    public RecheckScheduler(EndpointRepository endpoints,
                            AssessmentRepository assessments,
                            JobService jobService,
                            @Value("${app.jobs.recheck.posture-hours:4}") long postureHours,
                            @Value("${app.jobs.recheck.hardware-hours:24}") long hardwareHours,
                            @Value("${app.jobs.recheck.failure-backoff-hours:6}") long failureBackoffHours) {
        this.endpoints = endpoints;
        this.assessments = assessments;
        this.jobService = jobService;
        this.postureInterval = Duration.ofHours(postureHours);
        this.hardwareInterval = Duration.ofHours(hardwareHours);
        this.failureBackoff = Duration.ofHours(failureBackoffHours);
    }

    @Scheduled(
            fixedDelayString = "${app.jobs.recheck.sweep-interval-ms:300000}",
            initialDelayString = "${app.jobs.recheck.sweep-interval-ms:300000}")
    public void sweep() {
        try {
            int posture = 0;
            int hardware = 0;

            for (Endpoint ep : endpoints.findAllByConnectedTrue()) {
                if (!hasTarget(ep)) continue;

                if (jobService.enqueueIfDue(ep.getId(), JobType.POSTURE_CHECK, postureInterval, failureBackoff)) {
                    posture++;
                }

                // A posture result that is not ERROR is our proxy for "reachable Windows box".
                if (assessments.existsByEndpointIdAndStatusNot(ep.getId(), AssessmentStatus.ERROR)
                        && jobService.enqueueIfDue(ep.getId(), JobType.HARDWARE_CHECK, hardwareInterval, failureBackoff)) {
                    hardware++;
                }
            }

            if (posture > 0 || hardware > 0) {
                log.info("Recheck sweep queued {} posture and {} hardware job(s)", posture, hardware);
            }
        } catch (Exception e) {
            log.error("Recheck sweep failed (will retry next interval)", e); // never kill the scheduler
        }
    }

    private static boolean hasTarget(Endpoint ep) {
        return (ep.getIpAddress() != null && !ep.getIpAddress().isBlank())
                || (ep.getHostname() != null && !ep.getHostname().isBlank());
    }
}