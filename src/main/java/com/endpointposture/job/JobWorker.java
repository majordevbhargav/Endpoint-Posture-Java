package com.endpointposture.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background job poller - the Spring equivalent of posture_ui.py's
 * auto_worker() thread, minus the flat-file locking, since claiming now
 * happens safely inside Postgres via PostureJobRepository.findNextClaimable().
 *
 * Stage 3 stub: this does NOT yet dispatch a real PowerShell/WinRM posture
 * check. It only proves the claim/complete/fail mechanics are correct in
 * isolation. Real dispatch (Stage 4) replaces the body of runStubJob()
 * without touching anything else in this class.
 */
@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobService jobService;

    public JobWorker(JobService jobService) {
        this.jobService = jobService;
    }

    @Scheduled(fixedDelayString = "${app.jobs.poll-interval-ms:3000}")
    public void pollAndRun() {
        jobService.claimNextJob().ifPresent(this::runStubJob);
    }

    private void runStubJob(PostureJob job) {
        String mac = job.getEndpoint().getMacAddress();

        try {
            log.info("STUB: would run {} against endpoint {} (job {})",
                    job.getJobType(), mac, job.getId());

            // Real dispatch (PowerShell/WinRM) replaces this block in Stage 4.

            jobService.markComplete(job.getId());
            log.info("STUB: job {} marked COMPLETE", job.getId());

        } catch (Exception e) {
            log.error("STUB: job {} failed: {}", job.getId(), e.getMessage());
            jobService.markFailed(job.getId(), e.getMessage());
        }
    }
}