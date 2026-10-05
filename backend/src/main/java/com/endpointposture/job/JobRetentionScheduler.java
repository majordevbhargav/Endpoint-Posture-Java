package com.endpointposture.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link JobRetentionService} nightly (default 03:45, after the inventory
 * prune at 03:30). Shares the {@code app.retention.enabled} switch. Never throws
 * into the scheduler, so one failed run cannot stop future runs.
 */
@Component
@ConditionalOnProperty(name = "app.retention.enabled", havingValue = "true", matchIfMissing = true)
public class JobRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobRetentionScheduler.class);

    private final JobRetentionService service;

    public JobRetentionScheduler(JobRetentionService service) {
        this.service = service;
    }

    @Scheduled(cron = "${app.retention.jobs-cron:0 45 3 * * *}")
    public void run() {
        try {
            int deleted = service.pruneOldJobs();
            if (deleted > 0) {
                log.info("Job retention deleted {} finished job(s)", deleted);
            }
        } catch (Exception e) {
            log.error("Job retention failed (will retry next run)", e);
        }
    }
}