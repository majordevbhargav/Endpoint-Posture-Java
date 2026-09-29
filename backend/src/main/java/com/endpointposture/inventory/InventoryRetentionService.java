package com.endpointposture.inventory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bounds the size of {@code endpoint_inventory}. Assessments and check
 * results are evidence and are kept forever; the bulky raw inventory is
 * kept in full only for the newest N runs per endpoint. Older rows keep
 * their id, endpoint, assessment link and timestamp, but their JSONB
 * payloads are set to NULL. Idempotent: rows already pruned are skipped.
 */
@Service
public class InventoryRetentionService {

    private static final String PRUNE_SQL = """
            UPDATE endpoint_inventory
               SET listening_ports = NULL,
                   installed_apps  = NULL,
                   top_processes   = NULL,
                   resource_usage  = NULL
             WHERE id IN (
                   SELECT id FROM (
                       SELECT id,
                              ROW_NUMBER() OVER (PARTITION BY endpoint_id ORDER BY collected_at DESC) AS rn
                         FROM endpoint_inventory
                   ) ranked
                   WHERE ranked.rn > ?)
               AND (listening_ports IS NOT NULL OR installed_apps IS NOT NULL
                    OR top_processes IS NOT NULL OR resource_usage IS NOT NULL)
            """;

    private final JdbcTemplate jdbc;
    private final int keepRuns;

    public InventoryRetentionService(JdbcTemplate jdbc,
                                     @Value("${app.retention.inventory-keep-runs:10}") int keepRuns) {
        if (keepRuns < 1) {
            throw new IllegalArgumentException("app.retention.inventory-keep-runs must be at least 1");
        }
        this.jdbc = jdbc;
        this.keepRuns = keepRuns;
    }

    /** @return how many rows had their payloads cleared */
    @Transactional
    public int pruneOldPayloads() {
        return jdbc.update(PRUNE_SQL, keepRuns);
    }
}