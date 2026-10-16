package com.endpointposture.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Runs {@link EvidenceRetentionService} nightly (default 04:15, after the inventory and job prunes). Never throws into the scheduler. */
@Component
@ConditionalOnProperty(name = "app.retention.enabled", havingValue = "true", matchIfMissing = true)
public class EvidenceRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(EvidenceRetentionScheduler.class);

    private final EvidenceRetentionService service;

    public EvidenceRetentionScheduler(EvidenceRetentionService service) {
        this.service = service;
    }

    @Scheduled(cron = "${app.retention.evidence-cron:0 15 4 * * *}")
    public void run() {
        try {
            Map<String, Long> deleted = service.prune();
            long total = deleted.values().stream().mapToLong(Long::longValue).sum();
            if (total > 0) {
                log.info("Evidence retention deleted {} row(s): {}", total, deleted);
            }
        } catch (Exception e) {
            log.error("Evidence retention failed (will retry next run)", e);
        }
    }
}