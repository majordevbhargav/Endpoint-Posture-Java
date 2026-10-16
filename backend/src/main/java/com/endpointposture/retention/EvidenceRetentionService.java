package com.endpointposture.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Enforces the evidence retention window (DECISIONS D10: 90 days).
 *
 * <p>Evidence tables are pruned with batched deletes (default 5000 rows per
 * statement, each its own transaction) so a nightly run never holds a long lock
 * or builds a huge transaction. Partitioning was not needed for a 90-day window
 * (about 11M assessment rows at the 20k-device target) and would have forced
 * composite foreign keys; see DECISIONS D10.</p>
 *
 * <p>Protections, so retention can never make a device look like it has no
 * state:</p>
 * <ul>
 *   <li>an assessment still referenced by {@code endpoint.latest_assessment_id} is kept;</li>
 *   <li>hardware rows referenced by {@code latest_hw_id} or {@code good_hw_id} are kept;</li>
 *   <li>the newest diagnostic, security-indicator and inventory row of each endpoint is kept;</li>
 *   <li>the newest RESTRICT or CLEAR_RESTRICTION audit row per endpoint is kept, because
 *       {@code /ise/actions/state} derives the current enforcement state from it.</li>
 * </ul>
 * Deleting an assessment cascades to its check results; deleting a hardware row
 * cascades to its recommendations. Never calls ISE.
 */
@Service
public class EvidenceRetentionService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceRetentionService.class);

    private static final String ASSESSMENT = """
            DELETE FROM assessment WHERE id IN (
                SELECT a.id FROM assessment a
                 WHERE a.created_at < ?
                   AND NOT EXISTS (SELECT 1 FROM endpoint e WHERE e.latest_assessment_id = a.id)
                 LIMIT ?)
            """;

    private static final String HARDWARE = """
            DELETE FROM hardware_health WHERE id IN (
                SELECT h.id FROM hardware_health h
                 WHERE h.collected_at < ?
                   AND NOT EXISTS (SELECT 1 FROM endpoint e WHERE e.good_hw_id = h.id)
                   AND NOT EXISTS (SELECT 1 FROM endpoint e WHERE e.latest_hw_id = h.id)
                 LIMIT ?)
            """;

    /** %1$s = table name (a constant in this class, never user input). */
    private static final String KEEP_NEWEST = """
            DELETE FROM %1$s WHERE id IN (
                SELECT d.id FROM %1$s d
                 WHERE d.collected_at < ?
                   AND EXISTS (SELECT 1 FROM %1$s n
                                WHERE n.endpoint_id = d.endpoint_id AND n.collected_at > d.collected_at)
                 LIMIT ?)
            """;

    private static final String SESSION_LOG = """
            DELETE FROM endpoint_session_log WHERE id IN (
                SELECT id FROM endpoint_session_log WHERE event_at < ? LIMIT ?)
            """;

    private static final String AUDIT = """
            DELETE FROM ise_action_audit WHERE id IN (
                SELECT a.id FROM ise_action_audit a
                 WHERE a.occurred_at < ?
                   AND NOT (a.action_type IN ('RESTRICT','CLEAR_RESTRICTION')
                            AND NOT EXISTS (SELECT 1 FROM ise_action_audit n
                                             WHERE n.endpoint_id = a.endpoint_id
                                               AND n.action_type IN ('RESTRICT','CLEAR_RESTRICTION')
                                               AND n.occurred_at > a.occurred_at))
                 LIMIT ?)
            """;

    private static final String FEED_AUDIT = """
            DELETE FROM ise_feed_audit WHERE id IN (
                SELECT id FROM ise_feed_audit WHERE occurred_at < ? LIMIT ?)
            """;

    private final JdbcTemplate jdbc;
    private final int evidenceDays;
    private final int auditDays;
    private final int batchSize;
    private final int maxBatches;

    public EvidenceRetentionService(JdbcTemplate jdbc,
                                    @Value("${app.retention.evidence-days:90}") int evidenceDays,
                                    @Value("${app.retention.audit-days:90}") int auditDays,
                                    @Value("${app.retention.evidence-batch-size:5000}") int batchSize,
                                    @Value("${app.retention.evidence-max-batches:200}") int maxBatches) {
        if (evidenceDays < 1 || auditDays < 1) {
            throw new IllegalArgumentException("app.retention.evidence-days and audit-days must be at least 1");
        }
        if (batchSize < 1 || maxBatches < 1) {
            throw new IllegalArgumentException("app.retention.evidence-batch-size and evidence-max-batches must be at least 1");
        }
        this.jdbc = jdbc;
        this.evidenceDays = evidenceDays;
        this.auditDays = auditDays;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
    }

    /**
     * Runs every prune once. Not transactional on purpose: each batch commits on its own.
     *
     * @return rows deleted per table (cascaded child rows are not counted)
     */
    public Map<String, Long> prune() {
        Timestamp evidenceCutoff = Timestamp.from(Instant.now().minus(Duration.ofDays(evidenceDays)));
        Timestamp auditCutoff = Timestamp.from(Instant.now().minus(Duration.ofDays(auditDays)));

        Map<String, Long> out = new LinkedHashMap<>();
        out.put("assessment", loop(ASSESSMENT, evidenceCutoff));
        out.put("hardware_health", loop(HARDWARE, evidenceCutoff));
        out.put("endpoint_diagnostic", loop(KEEP_NEWEST.formatted("endpoint_diagnostic"), evidenceCutoff));
        out.put("endpoint_security_indicator", loop(KEEP_NEWEST.formatted("endpoint_security_indicator"), evidenceCutoff));
        out.put("endpoint_inventory", loop(KEEP_NEWEST.formatted("endpoint_inventory"), evidenceCutoff));
        out.put("endpoint_session_log", loop(SESSION_LOG, evidenceCutoff));
        out.put("ise_action_audit", loop(AUDIT, auditCutoff));
        out.put("ise_feed_audit", loop(FEED_AUDIT, auditCutoff));
        return out;
    }

    private long loop(String sql, Timestamp cutoff) {
        long total = 0;
        for (int i = 0; i < maxBatches; i++) {
            int n = jdbc.update(sql, cutoff, batchSize);
            total += n;
            if (n < batchSize) {
                return total;
            }
        }
        log.info("Evidence retention hit the per-run batch cap ({} x {}); the rest is taken next run",
                maxBatches, batchSize);
        return total;
    }
}