package com.endpointposture.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs {@link InventoryRetentionService} nightly. Never throws into the scheduler. */
@Component
@ConditionalOnProperty(name = "app.retention.enabled", havingValue = "true", matchIfMissing = true)
public class InventoryRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(InventoryRetentionScheduler.class);

    private final InventoryRetentionService service;

    public InventoryRetentionScheduler(InventoryRetentionService service) {
        this.service = service;
    }

    @Scheduled(cron = "${app.retention.inventory-cron:0 30 3 * * *}")
    public void run() {
        try {
            int pruned = service.pruneOldPayloads();
            if (pruned > 0) {
                log.info("Inventory retention cleared payloads on {} old row(s)", pruned);
            }
        } catch (Exception e) {
            log.error("Inventory retention failed (will retry next run)", e);
        }
    }
}