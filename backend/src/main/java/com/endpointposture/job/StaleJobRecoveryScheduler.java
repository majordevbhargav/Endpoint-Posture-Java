package com.endpointposture.job;

import com.endpointposture.diagnostic.DiagnosticService;
import com.endpointposture.diagnostic.config.DiagnosticAgentProperties;
import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.hardware.HardwareHealthService;
import com.endpointposture.hardware.config.HardwareAgentProperties;
import com.endpointposture.indicator.SecurityIndicatorService;
import com.endpointposture.indicator.config.SecurityIndicatorAgentProperties;
import com.endpointposture.posture.AssessmentService;
import com.endpointposture.posture.config.PostureAgentProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Recovers jobs stuck in RUNNING because the backend died or restarted mid-job.
 */
@Component
public class StaleJobRecoveryScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(StaleJobRecoveryScheduler.class);

    private final PostureJobRepository jobRepository;
    private final JobService jobService;
    private final AssessmentService assessmentService;
    private final HardwareHealthService hardwareHealthService;
    private final DiagnosticService diagnosticService;
    private final SecurityIndicatorService securityIndicatorService;
    private final PostureAgentProperties postureProps;
    private final HardwareAgentProperties hardwareProps;
    private final DiagnosticAgentProperties diagnosticProps;
    private final SecurityIndicatorAgentProperties securityProps;
    private final long marginSeconds;

    public StaleJobRecoveryScheduler(
            PostureJobRepository jobRepository,
            JobService jobService,
            AssessmentService assessmentService,
            HardwareHealthService hardwareHealthService,
            DiagnosticService diagnosticService,
            SecurityIndicatorService securityIndicatorService,
            PostureAgentProperties postureProps,
            HardwareAgentProperties hardwareProps,
            DiagnosticAgentProperties diagnosticProps,
            SecurityIndicatorAgentProperties securityProps,
            @Value("${app.jobs.stale-margin-seconds:60}") long marginSeconds) {

        this.jobRepository = jobRepository;
        this.jobService = jobService;
        this.assessmentService = assessmentService;
        this.hardwareHealthService = hardwareHealthService;
        this.diagnosticService = diagnosticService;
        this.securityIndicatorService = securityIndicatorService;
        this.postureProps = postureProps;
        this.hardwareProps = hardwareProps;
        this.diagnosticProps = diagnosticProps;
        this.securityProps = securityProps;
        this.marginSeconds = marginSeconds;
    }

    @PostConstruct
    public void recoverOnStartup() {
        sweep();
    }

    @Scheduled(fixedDelayString = "${app.jobs.stale-sweep-interval-ms:60000}")
    public void sweep() {
        try {
            long widestTimeoutSeconds = Math.max(
                    Math.max(
                            Math.max(
                                    postureProps.getProcessTimeoutSeconds(),
                                    hardwareProps.getProcessTimeoutSeconds()),
                            diagnosticProps.getProcessTimeoutSeconds()),
                    securityProps.getProcessTimeoutSeconds());

            Instant widestCutoff =
                    Instant.now().minusSeconds(widestTimeoutSeconds + marginSeconds);

            List<PostureJob> candidates =
                    jobRepository.findStaleRunning(widestCutoff);

            if (candidates.isEmpty()) {
                return;
            }

            int recovered = 0;
            for (PostureJob job : candidates) {
                if (isActuallyStale(job) && recover(job)) {
                    recovered++;
                }
            }

            if (recovered > 0) {
                log.warn(
                        "Stale-job sweep recovered {} job(s) stuck in RUNNING",
                        recovered);
            }
        } catch (Exception e) {
            log.error(
                    "Stale-job recovery sweep failed (will retry next interval)",
                    e);
        }
    }

    private long timeoutSecondsFor(PostureJob job) {
        return switch (job.getJobType()) {
            case POSTURE_CHECK -> postureProps.getProcessTimeoutSeconds();
            case HARDWARE_CHECK -> hardwareProps.getProcessTimeoutSeconds();
            case DIAGNOSTIC_CHECK -> diagnosticProps.getProcessTimeoutSeconds();
            case SECURITY_CHECK -> securityProps.getProcessTimeoutSeconds();
        };
    }

    private boolean isActuallyStale(PostureJob job) {
        Instant cutoff = Instant.now().minusSeconds(
                timeoutSecondsFor(job) + marginSeconds);

        return job.getStartedAt() != null
                && job.getStartedAt().isBefore(cutoff);
    }

    private boolean recover(PostureJob job) {
        String reason =
                "Recovered: worker died or the backend restarted mid-job (was RUNNING since "
                        + job.getStartedAt() + ")";

        boolean wasRunning =
                jobService.markFailedIfRunning(job.getId(), reason);

        if (!wasRunning) {
            return false;
        }

        Endpoint endpoint = job.getEndpoint();

        switch (job.getJobType()) {
            case POSTURE_CHECK ->
                    assessmentService.recordFailure(
                            endpoint.getId(), job.getId(), reason);
            case HARDWARE_CHECK ->
                    hardwareHealthService.recordFailure(
                            endpoint.getId(), job.getId(), reason);
            case DIAGNOSTIC_CHECK ->
                    diagnosticService.recordFailure(
                            endpoint.getId(), job.getId(), reason);
            case SECURITY_CHECK ->
                    securityIndicatorService.recordFailure(
                            endpoint.getId(), job.getId(), reason);
        }

        log.info(
                "Recovered stale job {} ({}) for endpoint {}",
                job.getId(),
                job.getJobType(),
                endpoint.getMacAddress());

        return true;
    }
}
